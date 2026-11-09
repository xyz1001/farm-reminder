package com.local.farmreminder;

final class SupportPromptPresentation {
    static final String TITLE = "赞赏支持";
    static final String AUTOMATIC_MESSAGE = "如果农场提醒对你有帮助，愿意赞赏支持吗？点击“同意”会将赞赏码保存到相册（Pictures/FarmReminder），并打开微信，请手动选择“扫一扫 → 相册”。赞赏完全自愿，不影响监测；拒绝或关闭后不会再自动询问。";
    static final String MANUAL_MESSAGE = "感谢你的支持。点击“同意”会将下方赞赏码保存到相册（Pictures/FarmReminder），并打开微信，请手动选择“扫一扫 → 相册”。赞赏完全自愿，不影响监测。";
    static final String SAVED = "赞赏码已保存到相册（Pictures/FarmReminder），请在微信中选择“扫一扫 → 相册”。";
    static final String NOT_SAVED = "未保存，可稍后在设置中重试。";
    static final String WECHAT_UNAVAILABLE = "赞赏码已保存，请安装或手动打开微信后从相册识别。";

    private SupportPromptPresentation() {}
}
