#import "GPUPixelCameraView.h"
#import <AVFoundation/AVFoundation.h>

#include <memory>
#include <vector>
#include "gpupixel/gpupixel.h"
#include "gpupixel/sink/sink_view.h"

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
#include "gpupixel/face_detector/face_detector.h"
#endif

using namespace gpupixel;

@interface GPUPixelCameraView () <AVCaptureVideoDataOutputSampleBufferDelegate>

@property (nonatomic, strong) AVCaptureSession*           captureSession;
@property (nonatomic, strong) AVCaptureVideoDataOutput*   videoOutput;
@property (nonatomic, strong) dispatch_queue_t            cameraQueue;

@end

@implementation GPUPixelCameraView {
  std::shared_ptr<SourceRawData>     _source;
  std::shared_ptr<SinkView>          _sinkView;
  std::shared_ptr<BeautyFaceFilter>  _beauty;
  std::shared_ptr<FaceReshapeFilter> _reshape;
  std::shared_ptr<BlusherFilter>     _blusher;

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  std::shared_ptr<FaceDetector>      _faceDetector;
#endif

  BOOL _pipelineReady;
  BOOL _starting;

  // Tham số hiện tại (0 – 10), giữ lại để áp dụng khi pipeline sẵn sàng.
  float _smoothing;
  float _whitening;
  float _faceSlim;
  float _eyeEnlarge;
  float _blusherLevel;
}

- (instancetype)initWithFrame:(CGRect)frame {
  self = [super initWithFrame:frame];
  if (self) {
    _cameraQueue = dispatch_queue_create("com.gpupixel.camera", DISPATCH_QUEUE_SERIAL);
    _pipelineReady = NO;
    _starting = NO;
  }
  return self;
}

// ---------------------------------------------------------------------------
// Lifecycle tự động: camera tự bật khi view xuất hiện (có window + bounds hợp
// lệ) và tự tắt khi bị gỡ. JS chỉ cần render view + truyền props — KHÔNG cần gọi
// imperative startCamera/stopCamera (vốn không hoạt động trên New Architecture
// vì RCTUIManager không giữ Fabric/interop view trong viewRegistry).
// ---------------------------------------------------------------------------

- (void)didMoveToWindow {
  [super didMoveToWindow];
  if (self.window) {
    [self _maybeStartCamera];
  } else {
    [self stopCamera];
  }
}

- (void)layoutSubviews {
  [super layoutSubviews];
  [self _maybeStartCamera];
}

- (void)_maybeStartCamera {
  if (_pipelineReady || _starting) return;  // đang chạy / đang khởi tạo
  if (!self.window) return;                 // chưa gắn vào màn hình
  if (CGRectIsEmpty(self.bounds)) return;   // SinkView cần bounds hợp lệ
  [self startCamera];
}

- (void)startCamera {
  // Camera chỉ hoạt động trên thiết bị thật — Simulator không có camera.
#if TARGET_OS_SIMULATOR
  NSLog(@"[GPUPixel] Camera không khả dụng trên iOS Simulator. "
        @"Hãy build & run trên thiết bị thật.");
  return;
#endif

  _starting = YES;

  // Yêu cầu quyền truy cập camera trước khi cấu hình session.
  AVAuthorizationStatus status =
      [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];

  switch (status) {
    case AVAuthorizationStatusAuthorized: {
      [self _startCameraGranted];
      break;
    }
    case AVAuthorizationStatusNotDetermined: {
      __weak GPUPixelCameraView* weakSelf = self;
      [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo
                               completionHandler:^(BOOL granted) {
        dispatch_async(dispatch_get_main_queue(), ^{
          GPUPixelCameraView* strongSelf = weakSelf;
          if (!strongSelf) return;
          if (granted) {
            [strongSelf _startCameraGranted];
          } else {
            strongSelf->_starting = NO;
            NSLog(@"[GPUPixel] Người dùng từ chối quyền camera.");
          }
        });
      }];
      break;
    }
    case AVAuthorizationStatusDenied:
    case AVAuthorizationStatusRestricted: {
      _starting = NO;
      NSLog(@"[GPUPixel] Quyền camera bị từ chối/giới hạn. "
            @"Vào Settings để cấp lại quyền.");
      break;
    }
  }
}

- (void)_startCameraGranted {
  [self _setupGPUPixelPipeline];
  [self _setupCaptureSession];
}

- (void)stopCamera {
  [self.captureSession stopRunning];
  self.captureSession = nil;

  _source.reset();
  _sinkView.reset();
  _beauty.reset();
  _reshape.reset();
  _blusher.reset();
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  _faceDetector.reset();
#endif
  _pipelineReady = NO;
  _starting = NO;
}

// ---------------------------------------------------------------------------
// GPUPixel pipeline
// ---------------------------------------------------------------------------

- (void)_setupGPUPixelPipeline {
  // Resource path: bundle ứa cài sẵn trong app
  NSString* resPath = [[NSBundle mainBundle] resourcePath];
  GPUPixel::SetResourceRoot([resPath UTF8String]);

  _source   = SourceRawData::Create();
  _sinkView = SinkView::Create((__bridge void*)self);
  _beauty   = BeautyFaceFilter::Create();
  _reshape  = FaceReshapeFilter::Create();
  _blusher  = BlusherFilter::Create();

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  _faceDetector = FaceDetector::Create();
#endif

  // Pipeline: source → blusher → reshape → beauty → view
  _source->AddSink(_blusher);
  _blusher->AddSink(_reshape);
  _reshape->AddSink(_beauty);
  _beauty->AddSink(_sinkView);

  _pipelineReady = YES;
  _starting = NO;

  // Áp các tham số đã nhận qua props trước khi pipeline kịp sẵn sàng.
  [self _applyParams];
}

- (void)_applyParams {
  if (!_pipelineReady) return;
  _beauty->SetBlurAlpha(_smoothing / 10.0f);
  _beauty->SetWhite(_whitening / 20.0f);
  _reshape->SetFaceSlimLevel(_faceSlim / 200.0f);
  _reshape->SetEyeZoomLevel(_eyeEnlarge / 100.0f);
  _blusher->SetBlendLevel(_blusherLevel / 10.0f);
}

// ---------------------------------------------------------------------------
// AVCaptureSession setup
// ---------------------------------------------------------------------------

- (void)_setupCaptureSession {
  self.captureSession = [[AVCaptureSession alloc] init];
  [self.captureSession setSessionPreset:AVCaptureSessionPreset1280x720];

  // Input: camera trước
  AVCaptureDevice* camera = [AVCaptureDevice
      defaultDeviceWithDeviceType:AVCaptureDeviceTypeBuiltInWideAngleCamera
                        mediaType:AVMediaTypeVideo
                         position:AVCaptureDevicePositionFront];
  if (!camera) {
    NSLog(@"[GPUPixel] Không tìm thấy camera trước (front camera). "
          @"Trên Simulator điều này là bình thường — hãy chạy trên thiết bị thật.");
    return;
  }

  NSError* error = nil;
  AVCaptureDeviceInput* input = [AVCaptureDeviceInput deviceInputWithDevice:camera error:&error];
  if (error || ![self.captureSession canAddInput:input]) {
    NSLog(@"[GPUPixel] Không thể thêm camera input: %@", error.localizedDescription);
    return;
  }
  [self.captureSession addInput:input];

  // Output: BGRA frames → delegate
  self.videoOutput = [[AVCaptureVideoDataOutput alloc] init];
  self.videoOutput.videoSettings = @{
    (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
  };
  self.videoOutput.alwaysDiscardsLateVideoFrames = YES;
  [self.videoOutput setSampleBufferDelegate:self queue:self.cameraQueue];

  if (![self.captureSession canAddOutput:self.videoOutput]) return;
  [self.captureSession addOutput:self.videoOutput];

  // Xoay đúng chiều cho front camera
  AVCaptureConnection* conn = [self.videoOutput connectionWithMediaType:AVMediaTypeVideo];
  if (conn.isVideoOrientationSupported)
    conn.videoOrientation = AVCaptureVideoOrientationPortrait;
  if (conn.isVideoMirroringSupported)
    conn.videoMirrored = YES;

  [self.captureSession startRunning];
}

// ---------------------------------------------------------------------------
// Camera delegate — nhận frame và đưa vào GPUPixel
// ---------------------------------------------------------------------------

- (void)captureOutput:(AVCaptureOutput*)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection*)connection {
  if (!_pipelineReady) return;

  CVImageBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
  CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);

  const uint8_t* baseAddress = (const uint8_t*)CVPixelBufferGetBaseAddress(pixelBuffer);
  size_t width  = CVPixelBufferGetWidth(pixelBuffer);
  size_t height = CVPixelBufferGetHeight(pixelBuffer);
  size_t stride = CVPixelBufferGetBytesPerRow(pixelBuffer);

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  if (_faceDetector) {
    std::vector<float> landmarks = _faceDetector->Detect(
        baseAddress, (int)width, (int)height, (int)stride,
        GPUPIXEL_MODE_FMT_VIDEO, GPUPIXEL_FRAME_TYPE_BGRA);
    if (_blusher)  _blusher->SetFaceLandmarks(landmarks);
    if (_reshape)  _reshape->SetFaceLandmarks(landmarks);
  }
#endif

  _source->ProcessData(baseAddress, (int)width, (int)height, (int)stride,
                       GPUPIXEL_FRAME_TYPE_BGRA);

  CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
}

// ---------------------------------------------------------------------------
// Param setters — có thể gọi từ bất kỳ thread nào
// ---------------------------------------------------------------------------

- (void)setSmoothing:(float)value {
  _smoothing = value;
  if (_beauty) _beauty->SetBlurAlpha(value / 10.0f);
}

- (void)setWhitening:(float)value {
  _whitening = value;
  if (_beauty) _beauty->SetWhite(value / 20.0f);
}

- (void)setFaceSlim:(float)value {
  _faceSlim = value;
  if (_reshape) _reshape->SetFaceSlimLevel(value / 200.0f);
}

- (void)setEyeEnlarge:(float)value {
  _eyeEnlarge = value;
  if (_reshape) _reshape->SetEyeZoomLevel(value / 100.0f);
}

- (void)setBlusher:(float)value {
  _blusherLevel = value;
  if (_blusher) _blusher->SetBlendLevel(value / 10.0f);
}

@end
