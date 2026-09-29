package com.winlator.input;

/**
 * 中文输入过程回调。
 *
 * <p>所有回调方法都在<b>后台处理线程</b>上被调用；如果调用方需要更新 UI，
 * 请自行切回主线程（例如通过 {@code Activity.runOnUiThread}）。</p>
 */
public interface InputCallback {

    /**
     * 一批字符开始处理。
     *
     * @param totalChars 本批（含历史队列中累计）待处理字符总数
     */
    void onInputStarted(int totalChars);

    /**
     * 单个字符处理完成。
     *
     * @param ch    被处理的字符
     * @param index 当前是第几个（从 1 开始）
     * @param total 本批总字符数
     */
    void onCharProcessed(char ch, int index, int total);

    /**
     * 输入处理结束。
     *
     * @param success true 表示队列正常处理完毕；false 表示被中断或出错中止
     */
    void onInputComplete(boolean success);

    /**
     * 发生错误。
     *
     * @param message 错误描述
     */
    void onError(String message);
}
