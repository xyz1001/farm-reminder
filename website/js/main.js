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

// 图片灯箱放大逻辑（显式挂载到 window 确保 100% 触发）
window.openLightbox = function(src, title) {
  const modal = document.getElementById("lightbox-modal");
  const img = document.getElementById("lightbox-img");
  const titleEl = document.getElementById("lightbox-title");
  if (modal && img) {
    img.src = src;
    if (titleEl && title) {
      titleEl.textContent = title;
    }
    modal.style.display = "flex";
    document.body.style.overflow = "hidden";
  }
};

window.closeLightbox = function() {
  const modal = document.getElementById("lightbox-modal");
  if (modal) {
    modal.style.display = "none";
    document.body.style.overflow = "";
  }
};

// 监听 ESC 键关闭所有弹窗
document.addEventListener("keydown", function(e) {
  if (e.key === "Escape") {
    closeDownloadModal();
    window.closeLightbox();
  }
});

// 点击下载弹窗外部阴影关闭
document.addEventListener("click", function(e) {
  const downloadModal = document.getElementById("download-modal");
  if (downloadModal && e.target === downloadModal) {
    closeDownloadModal();
  }
  const lightboxModal = document.getElementById("lightbox-modal");
  if (lightboxModal && e.target === lightboxModal) {
    window.closeLightbox();
  }
});
