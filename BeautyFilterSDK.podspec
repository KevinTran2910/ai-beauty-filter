# =============================================================================
# BeautyFilterSDK.podspec — local CocoaPod cho AI Beauty Filter (GPUPixel + MNN)
#
# Pod này được bootstrap.sh nhúng vào app React Native qua:
#     pod 'BeautyFilterSDK', :path => '<gốc repo>'
#
# Nó gom 4 thứ vào app:
#   1. Lớp cầu nối ObjC++  (native-bridge/*.mm) — nối JS React Native với engine C++.
#   2. Engine dựng sẵn     (BeautyFilter.xcframework) — CocoaPods tự chọn slice
#                          device/simulator.
#   3. Tài nguyên          (res/ + models/) — copy vào bundle app để runtime đọc
#                          qua [[NSBundle mainBundle] resourcePath].
#   4. Face detection      (MNN.framework + libmars-face-kit.a) — CHỈ slice device
#                          (iphoneos); slice simulator build với face detector TẮT.
# =============================================================================
Pod::Spec.new do |s|
  s.name             = 'BeautyFilterSDK'
  s.version          = '1.0.0'
  s.summary          = 'AI Beauty Filter native bridge for React Native (GPUPixel + MNN).'
  s.description       = 'Cầu nối React Native cho engine làm đẹp GPUPixel + nhận diện khuôn mặt MNN.'
  s.homepage         = 'https://example.com/beautyfilter'
  s.license          = { :type => 'MIT' }
  s.author           = { 'BeautyFilter' => 'khanh.tran8@ntq-solution.com.vn' }
  s.platform         = :ios, '15.1'

  # Local pod (cài qua :path) — source này chỉ để thỏa validator, bị bỏ qua khi
  # cài bằng :path.
  s.source           = { :git => 'https://example.invalid/BeautyFilterSDK.git', :tag => s.version.to_s }

  # ---- 1. Lớp cầu nối ObjC++ (RN <-> GPUPixel C++) --------------------------
  s.source_files     = 'native-bridge/**/*.{h,mm}'

  # ---- 2. Engine làm đẹp dựng sẵn -------------------------------------------
  # xcframework chứa cả 2 slice (ios-arm64 + ios-arm64-simulator); CocoaPods tự
  # chọn đúng slice theo SDK đang build. Headers gpupixel/* nằm trong framework.
  s.vendored_frameworks = 'prebuilt-sdk/ios/BeautyFilter.xcframework'

  # ---- 3. Tài nguyên: copy res/ và models/ vào bundle app -------------------
  # Code gọi GetResourcePath("res/...") và ("models") tương đối với resourcePath
  # của mainBundle, nên 2 thư mục này phải ở gốc bundle. CocoaPods copy verbatim
  # (qua copy-resources script, KHÔNG nén PNG) nên LUT .png không bị hỏng.
  s.resources        = ['prebuilt-sdk/ios/res', 'prebuilt-sdk/ios/models']

  # ---- 4. System frameworks GPUPixel cần (cả device lẫn simulator) ----------
  s.frameworks       = 'UIKit', 'AVFoundation', 'CoreVideo', 'CoreMedia',
                       'CoreGraphics', 'QuartzCore', 'OpenGLES', 'Accelerate'

  # React Native bridge (RCTViewManager / RCTBridgeModule headers)
  s.dependency 'React-Core'

  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'CLANG_CXX_LIBRARY'           => 'libc++',
    # Header gpupixel/* (ngoài Headers của xcframework, thêm thư mục include).
    'HEADER_SEARCH_PATHS'         => '"$(PODS_TARGET_SRCROOT)/prebuilt-sdk/ios/include"',

    # ---- Slice DEVICE (iphoneos): bật face detection + link MNN & mars ------
    # Khớp với slice device của xcframework (build kèm GPUPIXEL_ENABLE_FACE_DETECTOR).
    'GCC_PREPROCESSOR_DEFINITIONS[sdk=iphoneos*]' => '$(inherited) GPUPIXEL_ENABLE_FACE_DETECTOR=1',
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]'       => '"$(PODS_TARGET_SRCROOT)/prebuilt-sdk/ios"',
    'LIBRARY_SEARCH_PATHS[sdk=iphoneos*]'         => '"$(PODS_TARGET_SRCROOT)/prebuilt-sdk/ios"',
    'OTHER_LDFLAGS[sdk=iphoneos*]'                => '$(inherited) -framework MNN -framework CoreML -framework Metal -l"mars-face-kit"',
    # Slice SIMULATOR: KHÔNG define face detector, KHÔNG link MNN/mars (chỉ có
    # slice device) -> chạy độc lập với làm mịn/trắng da.
  }

  s.requires_arc     = true
end
