/**
 * 农场提醒 (Farm Reminder) 官网交互脚本
 */

// 配置：直接在此处配置安装包直链（留空则弹出获取指引弹窗）
const DOWNLOAD_APK_URL = ""; 

function handleDownloadClick() {
  if (DOWNLOAD_APK_URL && DOWNLOAD_APK_URL.trim() !== "") {
    window.location.href = DOWNLOAD_APK_URL;
  } else {
    openDownloadModal();
  }
}

function openDownloadModal() {
  const modal = document.getElementById("download-modal");
  if (modal) {
    modal.style.display = "flex";
    document.body.style.overflow = "hidden";
  }
}

function closeDownloadModal() {
  const modal = document.getElementById("download-modal");
  if (modal) {
    modal.style.display = "none";
    document.body.style.overflow = "";
  }
}

// 监听 ESC 键关闭弹窗
document.addEventListener("keydown", function(e) {
  if (e.key === "Escape") {
    closeDownloadModal();
  }
});

// 点击弹窗外部阴影关闭
document.addEventListener("click", function(e) {
  const modal = document.getElementById("download-modal");
  if (modal && e.target === modal) {
    closeDownloadModal();
  }
});
