#include "gpupixel/sink/sink_view.h"
#include "sink/objc_view.h"

#if defined(GPUPIXEL_IOS) || defined(GPUPIXEL_MAC)

#if defined(GPUPIXEL_IOS)
#import <UIKit/UIKit.h>
#else
#import <AppKit/AppKit.h>
#endif

namespace gpupixel {

std::shared_ptr<SinkView> SinkView::Create(void* parent_view) {
  return std::shared_ptr<SinkView>(new SinkView(parent_view));
}

SinkView::SinkView(void* parent_view) {
#if defined(GPUPIXEL_IOS)
  UIView* parentUIView = (__bridge UIView*)parent_view;

  ObjcView* gpuPixelView = [[ObjcView alloc] initWithFrame:parentUIView.bounds];
  gpuPixelView.autoresizingMask =
      UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;

  [gpuPixelView setFillMode:(gpupixel::SinkRender::PreserveAspectRatio)];
  [parentUIView addSubview:gpuPixelView];

  render_view_ = (__bridge_retained void*)gpuPixelView;
#else
  NSView* parentNSView = (__bridge NSView*)parent_view;

  ObjcView* gpuPixelView = [[ObjcView alloc] initWithFrame:parentNSView.bounds];
  [gpuPixelView setAutoresizingMask:NSViewWidthSizable | NSViewHeightSizable];

  [gpuPixelView setFillMode:(gpupixel::SinkRender::PreserveAspectRatio)];
  [parentNSView addSubview:gpuPixelView
                positioned:NSWindowBelow
                relativeTo:nil];

  render_view_ = (__bridge_retained void*)gpuPixelView;
#endif
}

SinkView::~SinkView() {
  if (render_view_) {
    ObjcView* gpuPixelView = (__bridge ObjcView*)render_view_;
    [gpuPixelView removeFromSuperview];
    render_view_ = nullptr;
  }
}

void SinkView::Render() {
  ObjcView* gpuPixelView = (__bridge ObjcView*)render_view_;
  [gpuPixelView DoRender];
}

void SinkView::SetInputFramebuffer(
    std::shared_ptr<GPUPixelFramebuffer> framebuffer,
    RotationMode rotation_mode,
    int texIdx) {
  ObjcView* gpuPixelView = (__bridge ObjcView*)render_view_;
  [gpuPixelView SetInputFramebuffer:framebuffer
                       withRotation:rotation_mode
                            atIndex:texIdx];
}

bool SinkView::IsReady() const {
  ObjcView* gpuPixelView = (__bridge ObjcView*)render_view_;
  if ([gpuPixelView respondsToSelector:@selector(IsReady)]) {
    return [gpuPixelView IsReady];
  }
  return true;
}

void SinkView::ResetAndClean() {
  ObjcView* gpuPixelView = (__bridge ObjcView*)render_view_;
  if ([gpuPixelView respondsToSelector:@selector(unPrepared)]) {
    [gpuPixelView unPrepared];
  }
}

}  // namespace gpupixel

#endif
