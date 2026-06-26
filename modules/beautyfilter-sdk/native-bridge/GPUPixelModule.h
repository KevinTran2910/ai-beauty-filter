#pragma once

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

/**
 * GPUPixelModule
 *
 * React Native Native Module để điều khiển beauty filter từ JS/TS.
 * Expose các method lên JS thread để set params và quản lý lifecycle.
 */
@interface GPUPixelModule : RCTEventEmitter <RCTBridgeModule>
@end
