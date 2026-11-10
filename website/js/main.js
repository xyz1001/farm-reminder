/**
 * 农场提醒 (Farm Reminder) 官网交互脚本
 */

// 配置：由 GitHub Actions 在发布 tag 时自动注入最新版本直链
const DOWNLOAD_APK_URL = ""; 
const GITHUB_RAW_URL = "";   

function handleDownloadClick() {
  if (DOWNLOAD_APK_URL && DOWNLOAD_APK_URL.trim() !== "") {
    // 优先走境内免流高速加速源
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

// 图片灯箱放大逻辑
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

// 点击外部阴影关闭弹窗
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
