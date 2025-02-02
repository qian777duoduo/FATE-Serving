/*
 * Copyright 2019 The FATE Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.ai.fate.serving.guest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;

import com.webank.ai.eggroll.core.utils.ObjectTransform;
import com.webank.ai.fate.serving.adapter.processing.PostProcessing;
import com.webank.ai.fate.serving.adapter.processing.PreProcessing;
import com.webank.ai.fate.serving.bean.InferenceRequest;
import com.webank.ai.fate.serving.bean.ModelNamespaceData;
import com.webank.ai.fate.serving.bean.PostProcessingResult;
import com.webank.ai.fate.serving.bean.PreProcessingResult;
import com.webank.ai.fate.serving.core.bean.*;
import com.webank.ai.fate.serving.core.constant.InferenceRetCode;
import com.webank.ai.fate.serving.federatedml.PipelineTask;
import com.webank.ai.fate.serving.interfaces.ModelManager;
import com.webank.ai.fate.serving.manger.InferenceWorkerManager;
import com.webank.ai.fate.serving.utils.InferenceUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class DefaultGuestInferenceProvider implements GuestInferenceProvider, InitializingBean {
    private static final Logger LOGGER = LogManager.getLogger();
    @Autowired
    ModelManager modelManager;
    @Autowired
    CacheManager cacheManager;
    private PostProcessing postProcessing;
    private PreProcessing preProcessing;




    private static void logInference(Context context, InferenceRequest inferenceRequest, ModelNamespaceData modelNamespaceData, ReturnResult inferenceResult, long elapsed, boolean getRemotePartyResult, boolean billing) {
        InferenceUtils.logInference(context, FederatedInferenceType.INITIATED, modelNamespaceData.getLocal(), modelNamespaceData.getRole(), inferenceRequest.getCaseid(), inferenceRequest.getSeqno(), inferenceResult.getRetcode(), elapsed, getRemotePartyResult, billing, new ObjectMapper().convertValue(inferenceRequest, HashMap.class), inferenceResult);
    }

    private static void logInference(Context context, Map<String, Object> federatedParams, FederatedParty federatedParty, FederatedRoles federatedRoles, ReturnResult inferenceResult, long elapsed, boolean getRemotePartyResult, boolean billing) {
        InferenceUtils.logInference(context, FederatedInferenceType.FEDERATED, federatedParty, federatedRoles, federatedParams.get(Dict.CASEID).toString(), federatedParams.get(Dict.SEQNO).toString(), inferenceResult.getRetcode(), elapsed, getRemotePartyResult, billing, federatedParams, inferenceResult);
    }

    public ReturnResult runInference(Context context, InferenceRequest inferenceRequest) {
        long startTime = System.currentTimeMillis();

        context.setCaseId(inferenceRequest.getCaseid());

        ReturnResult inferenceResult = new ReturnResult();
        inferenceResult.setCaseid(inferenceRequest.getCaseid());
        String modelName = inferenceRequest.getModelVersion();
        String modelNamespace = inferenceRequest.getModelId();
        if (StringUtils.isEmpty(modelNamespace) && inferenceRequest.haveAppId()) {
            modelNamespace = modelManager.getModelNamespaceByPartyId(inferenceRequest.getAppid());
        }
        if (StringUtils.isEmpty(modelNamespace)) {
            inferenceResult.setRetcode(InferenceRetCode.LOAD_MODEL_FAILED + 1000);
            return inferenceResult;
        }
        ModelNamespaceData modelNamespaceData = modelManager.getModelNamespaceData(modelNamespace);
        PipelineTask model;
        if (StringUtils.isEmpty(modelName)) {
            modelName = modelNamespaceData.getUsedModelName();
            model = modelNamespaceData.getUsedModel();
        } else {
            model = modelManager.getModel(modelName, modelNamespace);
        }
        if (model == null) {
            inferenceResult.setRetcode(InferenceRetCode.LOAD_MODEL_FAILED + 1000);
            return inferenceResult;
        }
        LOGGER.info("use model to inference for {}, id: {}, version: {}", inferenceRequest.getAppid(), modelNamespace, modelName);
        Map<String, Object> rawFeatureData = inferenceRequest.getFeatureData();

        if (rawFeatureData == null) {
            inferenceResult.setRetcode(InferenceRetCode.EMPTY_DATA + 1000);
            inferenceResult.setRetmsg("Can not parse data json.");
            logInference(context, inferenceRequest, modelNamespaceData, inferenceResult, 0, false, false);
            return inferenceResult;
        }

        PreProcessingResult preProcessingResult;
        try {

            preProcessingResult = getPreProcessingFeatureData(context, rawFeatureData);
        } catch (Exception ex) {
            LOGGER.error("feature data preprocessing failed", ex);
            inferenceResult.setRetcode(InferenceRetCode.INVALID_FEATURE + 1000);
            inferenceResult.setRetmsg(ex.getMessage());
            return inferenceResult;
        }
        Map<String, Object> featureData = preProcessingResult.getProcessingResult();
        Map<String, Object> featureIds = preProcessingResult.getFeatureIds();
        if (featureData == null) {
            inferenceResult.setRetcode(InferenceRetCode.NUMERICAL_ERROR + 1000);
            inferenceResult.setRetmsg("Can not preprocessing data");
            logInference(context, inferenceRequest, modelNamespaceData, inferenceResult, 0, false, false);
            return inferenceResult;
        }
        Map<String, Object> predictParams = new HashMap<>();
        Map<String, Object> modelFeatureData = Maps.newHashMap(featureData);
        FederatedParams federatedParams = new FederatedParams();

        federatedParams.setCaseId(inferenceRequest.getCaseid());
        federatedParams.setSeqNo(inferenceRequest.getSeqno());
        federatedParams.setLocal(modelNamespaceData.getLocal());
        federatedParams.setModelInfo(new ModelInfo(modelName, modelNamespace));
        federatedParams.setRole(modelNamespaceData.getRole());
        federatedParams.setFeatureIdMap(featureIds);


        Map<String, Object> modelResult = model.predict(context, modelFeatureData, federatedParams);
        PostProcessingResult postProcessingResult;
        try {
            postProcessingResult = getPostProcessedResult(context, featureData, modelResult);
            inferenceResult = postProcessingResult.getProcessingResult();
        } catch (Exception ex) {
            LOGGER.error("model result postprocessing failed", ex);
            if(inferenceResult!=null) {
                inferenceResult.setRetcode(InferenceRetCode.COMPUTE_ERROR);
                inferenceResult.setRetmsg(ex.getMessage());
            }
        }
        inferenceResult = handleResult(context, inferenceRequest, modelNamespaceData, inferenceResult);


        return inferenceResult;
    }

    private ReturnResult handleResult(Context context, InferenceRequest inferenceRequest, ModelNamespaceData modelNamespaceData, ReturnResult inferenceResult) {

        boolean getRemotePartyResult = (boolean) context.getDataOrDefault(Dict.GET_REMOTE_PARTY_RESULT, false);
        boolean billing = true;
        try {
            int partyInferenceRetcode = 0;
            inferenceResult.setCaseid(context.getCaseId());
            ReturnResult federatedResult = context.getFederatedResult();
            if (!getRemotePartyResult) {
                billing = false;
            } else if (federatedResult != null) {

                if (federatedResult.getRetcode() == InferenceRetCode.GET_FEATURE_FAILED || federatedResult.getRetcode() == InferenceRetCode.INVALID_FEATURE || federatedResult.getRetcode() == InferenceRetCode.NO_FEATURE) {
                    billing = false;
                }


                if (federatedResult.getRetcode() != 0) {
                    partyInferenceRetcode += 2;
                    inferenceResult.setRetcode(federatedResult.getRetcode());
                }

            }
            if (inferenceResult.getRetcode() != 0) {
                partyInferenceRetcode += 1;
            }
            inferenceResult.setRetcode(inferenceResult.getRetcode() + partyInferenceRetcode * 1000);
            inferenceResult = postProcessing.handleResult(context, inferenceResult);
            return inferenceResult;
        } finally {
            long endTime = System.currentTimeMillis();
            long inferenceElapsed = endTime - context.getTimeStamp();
            logInference(context, inferenceRequest, modelNamespaceData, inferenceResult, inferenceElapsed, getRemotePartyResult, billing);

        }

    }

    private PreProcessingResult getPreProcessingFeatureData(Context context, Map<String, Object> originFeatureData) {
        long beginTime = System.currentTimeMillis();
        try {
            return preProcessing.getResult(context, ObjectTransform.bean2Json(originFeatureData));
        } finally {
            long endTime = System.currentTimeMillis();
            LOGGER.info("preprocess caseid {} cost time {}", context.getCaseId(), endTime - beginTime);
        }

    }

    private PostProcessingResult getPostProcessedResult(Context context, Map<String, Object> featureData, Map<String, Object> modelResult) {
        long beginTime = System.currentTimeMillis();
        try {
            return postProcessing.getResult(context, featureData, modelResult);
        } finally {
            long endTime = System.currentTimeMillis();
            LOGGER.info("postprocess caseid {} cost time {}", context.getCaseId(), endTime - beginTime);
        }
    }

    @Override
    public ReturnResult syncInference(Context context, InferenceRequest inferenceRequest) {
        long inferenceBeginTime = System.currentTimeMillis();
        ReturnResult cacheResult = getReturnResultFromCache(context, inferenceRequest);

        if (cacheResult != null) {
            return cacheResult;
        }

        ReturnResult inferenceResultFromCache = cacheManager.getInferenceResultCache(inferenceRequest.getAppid(), inferenceRequest.getCaseid());
        LOGGER.info("caseid {} query cache cost {}", inferenceRequest.getCaseid(), System.currentTimeMillis() - inferenceBeginTime);
        if (inferenceResultFromCache != null) {
            LOGGER.info("request caseId {} cost time {}  hit cache true", inferenceRequest.getCaseid(), System.currentTimeMillis() - inferenceBeginTime);
            return inferenceResultFromCache;
        }

        ReturnResult inferenceResult = runInference(context, inferenceRequest);
        if (inferenceResult != null && inferenceResult.getRetcode() == 0) {
            cacheManager.putInferenceResultCache(context, inferenceRequest.getAppid(), inferenceRequest.getCaseid(), inferenceResult);
        }

        return inferenceResult;
    }


    private ReturnResult getReturnResultFromCache(Context context, InferenceRequest inferenceRequest) {
        long inferenceBeginTime = System.currentTimeMillis();
        ReturnResult inferenceResultFromCache = cacheManager.getInferenceResultCache(inferenceRequest.getAppid(), inferenceRequest.getCaseid());
        LOGGER.info("caseid {} query cache cost {}", inferenceRequest.getCaseid(), System.currentTimeMillis() - inferenceBeginTime);
        if (inferenceResultFromCache != null) {
            LOGGER.info("request caseId {} cost time {}  hit cache true", inferenceRequest.getCaseid(), System.currentTimeMillis() - inferenceBeginTime);

        }
        return inferenceResultFromCache;
    }

    @Override
    public ReturnResult asynInference(Context context, InferenceRequest inferenceRequest) {
        long beginTime = System.currentTimeMillis();
        ReturnResult cacheResult = getReturnResultFromCache(context, inferenceRequest);
        if (cacheResult != null) {
            return cacheResult;
        }
        InferenceWorkerManager.exetute(new Runnable() {

            @Override
            public void run() {
                ReturnResult inferenceResult = null;
                Context subContext = context.subContext();
                subContext.preProcess();
                try {
                    subContext.setActionType(Dict.ACTION_TYPE_ASYNC_EXECUTE);
                    inferenceResult = runInference(subContext, inferenceRequest);
                    if (inferenceResult != null && inferenceResult.getRetcode() == 0) {
                        cacheManager.putInferenceResultCache(subContext, inferenceRequest.getAppid(), inferenceRequest.getCaseid(), inferenceResult);
                    }
                } catch (Throwable e) {
                    LOGGER.error("asynInference error", e);
                } finally {
                    subContext.postProcess(inferenceRequest, inferenceResult);
                }
            }
        });
        ReturnResult startInferenceJobResult = new ReturnResult();
        startInferenceJobResult.setRetcode(InferenceRetCode.OK);
        startInferenceJobResult.setCaseid(inferenceRequest.getCaseid());
        return startInferenceJobResult;
    }

    @Override
    public ReturnResult getResult(Context context, InferenceRequest inferenceRequest) {

        ReturnResult cacheResult = this.getReturnResultFromCache(context, inferenceRequest);
        if (cacheResult != null) {
            return cacheResult;
        }
        ReturnResult noCacheInferenceResult = new ReturnResult();
        noCacheInferenceResult.setRetcode(InferenceRetCode.NO_RESULT);
        return noCacheInferenceResult;

    }

    @Override
    public void afterPropertiesSet() throws Exception {

        try {
            String classPathPre = PostProcessing.class.getPackage().getName();
            String postClassPath = classPathPre + "." + Configuration.getProperty(Dict.POST_PROCESSING_CONFIG);
            postProcessing = (PostProcessing) InferenceUtils.getClassByName(postClassPath);
            String preClassPath = classPathPre + "." + Configuration.getProperty(Dict.PRE_PROCESSING_CONFIG);
            preProcessing = (PreProcessing) InferenceUtils.getClassByName(preClassPath);
        } catch (Throwable e) {
            LOGGER.error("load post/pre processing error", e);
        }

    }
}
