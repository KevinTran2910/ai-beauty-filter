# =============================================================================
# BeautyFilterSDK.podspec — local CocoaPod cho AI Beauty Filter (GPUPixel + MNN)
#
# Bản dành cho Expo. Pod được app Expo nạp qua config plugin (app.plugin.js):
#     pod 'BeautyFilterSDK', :path => '../modules/beautyfilter-sdk'
#
# native-bridge/ và prebuilt-sdk/ được setup.sh copy từ ../../ios vào CẠNH file
# podspec này, nên các path bên dưới là tương đối với thư mục module (giống hệt
# podspec bản bare React Native).
# =============================================================================
Pod::Spec.new do |s|
  s.name             = 'BeautyFilterSDK'
  s.version          = '1.0.0'
  s.summary          = 'AI Beauty Filter native bridge for Expo / React Native (GPUPixel + MNN).'
  s.description      = 'Cầu nối React Native cho engine làm đẹp GPUPixel + nhận diện khuôn mặt MNN.'
  s.homepage         = 'https://example.com/beautyfilter'
  s.license          = { :type => 'MIT' }
  s.author           = { 'BeautyFilter' => 'khanh.tran8@ntq-solution.com.vn' }
  s.platform         = :ios, '15.1'
  s.source           = { :git => 'https://example.invalid/BeautyFilterSDK.git', :tag => s.version.to_s }

  # ---- 1. Lớp cầu nối ObjC++ (RN <-> GPUPixel C++) --------------------------
  s.source_files     = 'native-bridge/**/*.{h,mm}'

  # ---- 2. Engine làm đẹp dựng sẵn -------------------------------------------
  s.vendored_frameworks = 'prebuilt-sdk/ios/BeautyFilter.xcframework'

  # ---- 3. Tài nguyên: copy res/ và models/ vào bundle app -------------------
  s.resources        = ['prebuilt-sdk/ios/res', 'prebuilt-sdk/ios/models']

  # ---- 4. System frameworks GPUPixel cần (cả device lẫn simulator) ----------
  s.frameworks       = 'UIKit', 'AVFoundation', 'CoreVideo', 'CoreMedia',
                       'CoreGraphics', 'QuartzCore', 'OpenGLES', 'Accelerate'

  # React Native bridge (RCTViewManager / RCTBridgeModule headers)
  s.dependency 'React-Core'

  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'CLANG_CXX_LIBRARY'           => 'libc++',
    'HEADER_SEARCH_PATHS'         => '"$(PODS_TARGET_SRCROOT)/prebuilt-sdk/ios/include"',

    # ---- Slice DEVICE (iphoneos): bật face detection + link MNN & mars ------
    'GCC_PREPROCESSOR_DEFINITIONS[sdk=iphoneos*]' => '$(inherited) GPUPIXEL_ENABLE_FACE_DETECTOR=1',
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]'       => '"$(PODS_TARGET_SRCROOT)/prebuilt-sdk/ios"',
    'LIBRARY_SEARCH_PATHS[sdk=iphoneos*]'         => '"$(PODS_TARGET_SRCROOT)/prebuilt-sdk/ios"',
    'OTHER_LDFLAGS[sdk=iphoneos*]'                => '$(inherited) -framework MNN -framework CoreML -framework Metal -l"mars-face-kit"',
  }

  s.requires_arc     = true
end
