// =============================================================================
// app.plugin.js — Expo Config Plugin cho BeautyFilter SDK (GPUPixel + MNN)
//
// Thay cho phần sửa tay trong bootstrap.sh (awk vá Podfile + PlistBuddy vá
// Info.plist). Mỗi lần chạy `expo prebuild`, Expo gọi plugin này để:
//   1. Chèn `pod 'BeautyFilterSDK', :path => '../modules/beautyfilter-sdk'` vào Podfile.
//   2. Thêm quyền NSPhotoLibraryUsageDescription + NSCameraUsageDescription vào Info.plist.
//
// Vì `expo prebuild` sinh lại thư mục ios/ nên KHÔNG được sửa Podfile/Info.plist
// bằng tay — plugin này là nơi duy nhất khai báo các thay đổi đó.
// =============================================================================
const { withDangerousMod, withInfoPlist } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

// Đường dẫn pod TƯƠNG ĐỐI so với thư mục ios/ của app (ios/ -> ../modules/...).
const POD_PATH = '../modules/beautyfilter-sdk';
const POD_LINE = `  # AI Beauty Filter local SDK (native bridge + prebuilt xcframework)\n  pod 'BeautyFilterSDK', :path => '${POD_PATH}'\n`;

const PERMISSIONS = {
  NSPhotoLibraryUsageDescription: 'App cần truy cập thư viện ảnh để chọn ảnh làm đẹp.',
  NSCameraUsageDescription: 'App cần camera cho chế độ làm đẹp real-time.',
};

/** 1. Chèn pod vào Podfile (idempotent — chạy lại nhiều lần không nhân đôi). */
function withBeautyFilterPod(config) {
  return withDangerousMod(config, [
    'ios',
    (cfg) => {
      const podfile = path.join(cfg.modRequest.platformProjectRoot, 'Podfile');
      let txt = fs.readFileSync(podfile, 'utf8');

      if (!txt.includes('BeautyFilterSDK')) {
        // Ưu tiên chèn sau `use_expo_modules!`; nếu không có thì chèn ngay sau
        // dòng `target '...' do` đầu tiên.
        if (/use_expo_modules!\s*\n/.test(txt)) {
          txt = txt.replace(/(use_expo_modules!\s*\n)/, `$1${POD_LINE}`);
        } else {
          txt = txt.replace(/(target\s+['"][^'"]+['"]\s+do\s*\n)/, `$1${POD_LINE}`);
        }
        fs.writeFileSync(podfile, txt);
      }
      return cfg;
    },
  ]);
}

/** 2. Thêm quyền ảnh/camera vào Info.plist. */
function withBeautyFilterPermissions(config) {
  return withInfoPlist(config, (cfg) => {
    for (const [key, value] of Object.entries(PERMISSIONS)) {
      cfg.modResults[key] = cfg.modResults[key] || value;
    }
    return cfg;
  });
}

module.exports = function withBeautyFilter(config) {
  config = withBeautyFilterPod(config);
  config = withBeautyFilterPermissions(config);
  return config;
};
