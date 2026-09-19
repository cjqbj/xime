package com.kingzcheung.xime.service;

interface IInferenceService {
    /** 加载模型 */
    boolean loadModel(String modelId, String modelPath, String extraPath);
    void unloadModel(String modelId);
    boolean isModelLoaded(String modelId);

    /** 智能联想预测 — 返回交替 [word, score, word, score, ...] */
    List<String> predict(String modelId, String text, int topK);

    /** 手写识别 — 返回交替 [index, score, index, score, ...] */
    List<String> recognizeHandwriting(String modelId, in float[] strokeData, in byte[] mask, int topK);

    /** 语音前处理（AGC 等） */
    byte[] processAudioBytes(in byte[] input, int sampleRate);
}
