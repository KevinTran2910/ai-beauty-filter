Pod::Spec.new do |s|
  s.name         = 'BeautyFilterSDK'
  s.version      = '0.1.0'
  s.summary      = 'AI Beauty Filter SDK bridge for React Native (iOS)'
  s.description  = 'React Native native bridge that wraps the prebuilt GPUPixel ' \
                   'BeautyFilter.xcframework (beauty / reshape / makeup filters + ' \
                   'face detection) for camera and static-image use.'
  s.homepage     = 'https://github.com/your-org/ai-beauty-filter'
  s.license      = { :type => 'MIT' }
  s.author       = { 'AI Beauty Filter' => 'dev@example.com' }
  s.platform     = :ios, '13.0'
  s.source       = { :path => '.' }

  # NOTE: This podspec lives at the REPO ROOT on purpose. CocoaPods silently
  # drops vendored_frameworks / resources that resolve outside the podspec
  # directory, so every referenced path below must be a child of the repo root.

  # ---- Native bridge sources (compiled as ObjC++) --------------------------
  s.source_files        = 'demo/react-native/sdk-ios/**/*.{h,mm}'
  s.public_header_files = 'demo/react-native/sdk-ios/**/*.h'

  # ---- Prebuilt SDK binary --------------------------------------------------
  # BeautyFilter.xcframework has two slices:
  #   * ios-arm64            (device)    — face detection ON  (needs MNN + mars)
  #   * ios-arm64-simulator  (simulator) — face detection OFF (self-contained)
  # CocoaPods links the correct slice per SDK automatically.
  s.vendored_frameworks = [
    'output/ios/BeautyFilter.xcframework',
  ]

  # res/ and models/ are copied into the app bundle root so that
  # GPUPixel::GetResourcePath("res/...") and ("models") resolve at runtime.
  s.resources = [
    'output/ios/res',
    'output/ios/models',
  ]

  # System frameworks. CoreML/Metal are only used by MNN (device) but are part of
  # the simulator SDK too, so linking them everywhere is harmless.
  s.frameworks = 'OpenGLES', 'AVFoundation', 'CoreVideo', 'CoreMedia',
                 'Accelerate', 'UIKit', 'Foundation', 'QuartzCore',
                 'CoreML', 'Metal'
  s.libraries  = 'c++'

  s.dependency 'React-Core'

  # ---- Build settings -------------------------------------------------------
  # Face detection (MNN + mars-face-kit) is DEVICE-ONLY: those static binaries
  # have no simulator slice. So the GPUPIXEL_ENABLE_FACE_DETECTOR macro (which
  # gates FaceDetector use in the bridge) is defined only for the device SDK.
  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS'          => '"$(PODS_TARGET_SRCROOT)/output/ios/include"',
    'CLANG_CXX_LANGUAGE_STANDARD'  => 'c++17',
    'CLANG_CXX_LIBRARY'            => 'libc++',
    'GCC_PREPROCESSOR_DEFINITIONS[sdk=iphoneos*]' => '$(inherited) GPUPIXEL_ENABLE_FACE_DETECTOR=1',
    # simulator xcframework slice is arm64-only → drop x86_64.
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64',
  }

  # Link MNN.framework + libmars-face-kit.a into the APP, DEVICE builds only.
  # Paths are relative to PODS_ROOT (= <app>/ios/Pods); repo root is 5 levels up
  # given the bootstrap layout demo/react-native/<App>/ios/Pods.
  repo_from_pods = '$(PODS_ROOT)/../../../../..'
  s.user_target_xcconfig = {
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64',
    "FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]" => "$(inherited) \"#{repo_from_pods}/output/ios\"",
    "LIBRARY_SEARCH_PATHS[sdk=iphoneos*]"   => "$(inherited) \"#{repo_from_pods}/third_party/mars-face-kit/libs/ios\"",
    "OTHER_LDFLAGS[sdk=iphoneos*]"          => '$(inherited) -framework MNN -lmars-face-kit',
  }
end
