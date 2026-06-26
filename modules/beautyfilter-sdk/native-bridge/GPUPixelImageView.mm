#import "GPUPixelImageView.h"

#include <memory>
#include <vector>

#include "gpupixel/gpupixel.h"
#include "gpupixel/sink/sink_view.h"

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
#include "gpupixel/face_detector/face_detector.h"
#endif

using namespace gpupixel;

@implementation GPUPixelImageView {
  std::shared_ptr<SourceRawData>     _source;
  std::shared_ptr<SinkView>          _sinkView;
  std::shared_ptr<BeautyFaceFilter>  _beauty;
  std::shared_ptr<FaceReshapeFilter> _reshape;
  std::shared_ptr<BlusherFilter>     _blusher;

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  std::shared_ptr<FaceDetector>      _faceDetector;
  std::vector<float>                 _landmarks;
#endif

  // Frame ảnh đã decode sang RGBA (top-left origin, đã bake orientation).
  std::vector<uint8_t> _rgba;
  int  _imgWidth;
  int  _imgHeight;

  // Tham số hiện tại (0 – 10), giữ lại để áp dụng khi pipeline sẵn sàng.
  float _smoothing;
  float _whitening;
  float _faceSlim;
  float _eyeEnlarge;
  float _blusher_;

  BOOL _pipelineReady;
  BOOL _hasImage;
  BOOL _reprocessScheduled;
}

- (instancetype)initWithFrame:(CGRect)frame {
  self = [super initWithFrame:frame];
  if (self) {
    _imgWidth = _imgHeight = 0;
    _smoothing = 0.0f;
    _whitening = 0.0f;
    _faceSlim = 0.0f;
    _eyeEnlarge = 0.0f;
    _blusher_ = 0.0f;
    _pipelineReady = NO;
    _hasImage = NO;
    _reprocessScheduled = NO;
    self.backgroundColor = [UIColor blackColor];
  }
  return self;
}

- (void)dealloc {
  [self _teardown];
}

- (void)_teardown {
  _source.reset();
  _sinkView.reset();
  _beauty.reset();
  _reshape.reset();
  _blusher.reset();
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  _faceDetector.reset();
#endif
  _pipelineReady = NO;
}

// SinkView tạo GL layer dựa trên bounds → đợi tới khi view có kích thước thật.
- (void)layoutSubviews {
  [super layoutSubviews];
  [self _scheduleReprocess];
}

// ---------------------------------------------------------------------------
// Props (gọi từ JSX qua RCTViewManager). Mọi thay đổi đều coalesce vào 1 lần
// reprocess ở cuối runloop để tránh render lại nhiều lần thừa.
// ---------------------------------------------------------------------------

- (void)setImageUri:(NSString*)uri {
  [self _loadImage:uri];
  [self _scheduleReprocess];
}

- (void)setSmoothing:(float)value   { _smoothing = value;  [self _scheduleReprocess]; }
- (void)setWhitening:(float)value   { _whitening = value;  [self _scheduleReprocess]; }
- (void)setFaceSlim:(float)value    { _faceSlim = value;   [self _scheduleReprocess]; }
- (void)setEyeEnlarge:(float)value  { _eyeEnlarge = value; [self _scheduleReprocess]; }
- (void)setBlusher:(float)value     { _blusher_ = value;   [self _scheduleReprocess]; }

// ---------------------------------------------------------------------------
// Decode ảnh → RGBA (đã chuẩn hoá orientation, top-left origin)
// ---------------------------------------------------------------------------

- (void)_loadImage:(NSString*)uri {
  _hasImage = NO;
  if (uri.length == 0) return;

  NSString* path = uri;
  if ([path hasPrefix:@"file://"]) {
    NSURL* url = [NSURL URLWithString:uri];
    path = url.path ?: [uri substringFromIndex:7];
  }

  UIImage* img = [UIImage imageWithContentsOfFile:path];
  if (!img || !img.CGImage) return;

  int w = (int)lround(img.size.width * img.scale);
  int h = (int)lround(img.size.height * img.scale);
  if (w <= 0 || h <= 0) return;

  _rgba.assign((size_t)w * h * 4, 0);

  CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
  CGContextRef ctx = CGBitmapContextCreate(
      _rgba.data(), w, h, 8, (size_t)w * 4, cs,
      kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big);
  CGColorSpaceRelease(cs);
  if (!ctx) return;

  // CGContext gốc bottom-left; UIImage drawInRect kỳ vọng top-left → lật trục Y
  // để bake đúng orientation EXIF của ảnh.
  UIGraphicsPushContext(ctx);
  CGContextTranslateCTM(ctx, 0, h);
  CGContextScaleCTM(ctx, 1.0, -1.0);
  [img drawInRect:CGRectMake(0, 0, w, h)];
  UIGraphicsPopContext();
  CGContextRelease(ctx);

  _imgWidth = w;
  _imgHeight = h;
  _hasImage = YES;
}

// ---------------------------------------------------------------------------
// Pipeline + render
// ---------------------------------------------------------------------------

- (void)_ensurePipeline {
  if (_pipelineReady) return;
  if (CGRectIsEmpty(self.bounds)) return;  // SinkView cần bounds hợp lệ

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
}

- (void)_applyParams {
  if (!_pipelineReady) return;
  _beauty->SetBlurAlpha(_smoothing / 10.0f);
  _beauty->SetWhite(_whitening / 20.0f);
  _reshape->SetFaceSlimLevel(_faceSlim / 200.0f);
  _reshape->SetEyeZoomLevel(_eyeEnlarge / 100.0f);
  _blusher->SetBlendLevel(_blusher_ / 10.0f);
}

- (void)_scheduleReprocess {
  if (_reprocessScheduled) return;
  _reprocessScheduled = YES;
  __weak GPUPixelImageView* weakSelf = self;
  dispatch_async(dispatch_get_main_queue(), ^{
    GPUPixelImageView* strongSelf = weakSelf;
    if (!strongSelf) return;
    strongSelf->_reprocessScheduled = NO;
    [strongSelf _reprocess];
  });
}

- (void)_reprocess {
  [self _ensurePipeline];
  if (!_pipelineReady || !_hasImage || _rgba.empty()) return;

  [self _applyParams];

  const uint8_t* data = _rgba.data();

#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  if (_faceDetector) {
    _landmarks = _faceDetector->Detect(
        data, _imgWidth, _imgHeight, _imgWidth * 4,
        GPUPIXEL_MODE_FMT_PICTURE, GPUPIXEL_FRAME_TYPE_RGBA);
    if (_blusher) _blusher->SetFaceLandmarks(_landmarks);
    if (_reshape) _reshape->SetFaceLandmarks(_landmarks);
  }
#endif

  _source->ProcessData(data, _imgWidth, _imgHeight, _imgWidth * 4,
                       GPUPIXEL_FRAME_TYPE_RGBA);
}

@end
