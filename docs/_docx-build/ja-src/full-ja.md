# システム概要

## システムの目的

本システムは、ネイティブの AI Beauty Filter SDK を iOS の React Native デモアプリ
内で利用できるようにパッケージ化したものです。デモは次の 2 つのワークフローに
焦点を当てています。

- **静止画処理:** フォトライブラリから画像を選択してデコードし、ビューティ
  パイプラインを実行して、結果をネイティブビューに描画します。
- **リアルタイムカメラ処理:** フロントカメラからフレームを取得し、顔検出と
  ビューティパイプラインを実行して、処理結果をライブ表示します。

## 主要コンポーネント

```text
ios/
├── bootstrap.sh
├── BeautyFilterSDK.podspec
├── native-bridge/
├── prebuilt-sdk/ios/
├── app-template/
└── BeautyFilterDemo/        # bootstrap.sh が生成
```

### `bootstrap.sh`

デモアプリを生成または更新します。主な責務は次のとおりです。

- React Native CLI の `init BeautyFilterDemo` を実行します。
- デモに必要な npm 依存関係をインストールします。
- `app-template/App.tsx` と `app-template/src/index.ts` を生成済みアプリへ
  オーバーレイします。
- ローカル pod `BeautyFilterSDK` を `BeautyFilterDemo/ios/Podfile` に追加します。
- カメラとフォトライブラリの利用目的説明を `Info.plist` に追加します。
- `pod install` を実行します。

生成される `BeautyFilterDemo/` は再現可能であり、SDK の正本ソースではありません。
いつでも再生成できます。

### `BeautyFilterSDK.podspec`

このローカル CocoaPod が、ネイティブブリッジとビルド済み SDK をアプリへ取り込み
ます。pod には次が含まれます。

- `native-bridge/**/*.{h,mm}` の ObjC++ ブリッジソース。
- `prebuilt-sdk/ios/BeautyFilter.xcframework`。
- `prebuilt-sdk/ios/res` と `prebuilt-sdk/ios/models` のランタイムリソース。
- デバイスビルド向けの `MNN.framework` と `libmars-face-kit.a` のリンク。
- `RCTViewManager` とネイティブモジュールのヘッダ用に `React-Core` への依存。

デバイスビルドでは podspec が次を設定します。

- `GPUPIXEL_ENABLE_FACE_DETECTOR=1`
- `MNN`、`CoreML`、`Metal`、`mars-face-kit` のリンク

シミュレータビルドでは MNN や mars をリンクしません。これらのバイナリは現行の
SDK パッケージではデバイススライスのみを提供するためです。

### `prebuilt-sdk/ios/`

ビルド済みのネイティブ SDK を格納します。本リポジトリは C++ エンジンを再ビルド
しません。主な内容は次のとおりです。

- `BeautyFilter.xcframework`: パッケージ化された GPUPixel エンジン（デバイス +
  シミュレータスライス）。
- `MNN.framework`: デバイスでの顔検出が使用する AI フレームワーク。
- `libmars-face-kit.a`: デバイスで使用するランドマーク／フェイスキットライブラリ。
- `include/`: GPUPixel のヘッダ。
- `res/`: ルックアップテーブルとランタイム画像リソース。
- `models/`: 顔検出モデル。

ネイティブのロードコードを更新しない限り、`res/` と `models/` を削除・再構成
しないでください。ブリッジは
`GPUPixel::SetResourceRoot([[NSBundle mainBundle] resourcePath])` を呼び出し、
アプリバンドルからの相対パスでリソースを解決します。

### `native-bridge/`

React Native と C++ エンジンを接続する ObjC++ ブリッジです。

- `GPUPixelImageView.{h,mm}`: 静止画処理用のネイティブビュー。
- `GPUPixelImageViewManager.{h,mm}`: `GPUPixelImageView` を React Native へ公開。
- `GPUPixelCameraView.{h,mm}`: リアルタイムカメラ処理用のネイティブビュー。
- `GPUPixelViewManager.{h,mm}`: `GPUPixelCameraView` を React Native へ公開。
- `GPUPixelModule.{h,mm}`: カメラの開始／停止とパラメータ更新を行う旧来の命令型
  ネイティブモジュール。現行のデモは、React Native New Architecture の相互運用に
  おいてより安定する prop 駆動方式を優先しています。

### `app-template/`

bootstrap 実行ごとに `BeautyFilterDemo` へコピーされる JS/TS ファイルです。

- `App.tsx`: 画像／カメラモード、画像ピッカー、スライダーを備えたデモ画面。
- `src/index.ts`: ネイティブコンポーネントの TypeScript ラッパー。

## 静止画フロー

1. JavaScript が `launchImageLibrary` を呼び出して `imageUri` を取得します。
2. `App.tsx` が `imageUri` とフィルタパラメータを渡して `GPUPixelImageView` を
   描画します。
3. `GPUPixelImageViewManager` が React Native の prop を ObjC++ のセッターへ
   マッピングします。
4. `GPUPixelImageView` が画像を RGBA にデコードし、向きを正規化します。
5. 有効な bounds を取得した時点でビューがネイティブパイプラインを初期化します。
6. 顔検出が有効なデバイスビルドでは、ネイティブコードがランドマークを検出します。
7. パイプラインがフレームを処理し、`SinkView` へ描画します。

現行パイプライン:

```text
SourceRawData -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter -> SinkView
```

パラメータマッピング:

| JS prop | UI 範囲 | ネイティブ変換 |
| --- | ---: | --- |
| `smoothing` | 0-10 | `SetBlurAlpha(value / 10)` |
| `whitening` | 0-10 | `SetWhite(value / 20)` |
| `faceSlim` | 0-10 | `SetFaceSlimLevel(value / 200)` |
| `eyeEnlarge` | 0-10 | `SetEyeZoomLevel(value / 100)` |
| `blusher` | 0-10 | `SetBlendLevel(value / 10)` |

## リアルタイムカメラフロー

1. JavaScript が `GPUPixelCameraView` を描画します。
2. ビューがウィンドウにアタッチされ、有効な bounds を持った時点でネイティブ
   ビューがカメラを開始します。
3. `AVCaptureSession` はフロントカメラ、プリセット `1280x720`、BGRA 出力を使用
   します。
4. キャプチャデリゲートがシリアルキュー上で各フレームを受け取ります。
5. 顔検出が有効なデバイスビルドでは、各フレームを VIDEO モードでランドマーク
   解析します。
6. BGRA フレームを `SourceRawData` へ送り、パイプライン経由で描画します。
7. ビューがウィンドウから外れると、カメラは自動的に停止します。

カメラモードは iOS シミュレータでは利用できません。

## シミュレータ対デバイス

| 機能 | シミュレータ | 実機 iPhone |
| --- | --- | --- |
| 肌のスムージング | あり | あり |
| 美白 | あり | あり |
| 顔ランドマーク | なし | あり |
| 小顔 | なし | あり |
| 目の拡大 | なし | あり |
| ランドマークベースのチーク | なし | あり |
| リアルタイムカメラ | なし | あり |

理由: `MNN.framework` と `libmars-face-kit.a` はデバイススライスのみを提供する
ため、シミュレータビルドでは `GPUPIXEL_ENABLE_FACE_DETECTOR` を有効化しません。

# 利用ガイド

## 環境要件

- macOS と Xcode。
- Node.js と npm。
- CocoaPods。
- カメラモードと完全な顔ランドマーク効果をテストする場合は実機 iPhone。
- 完全な `prebuilt-sdk/ios/` ディレクトリ。

簡易チェック:

```bash
node --version
npm --version
pod --version
```

## デモアプリのブートストラップ

リポジトリのルートから:

```bash
cd ios
./bootstrap.sh
```

スクリプトは `BeautyFilterDemo/` を生成または更新し、npm パッケージを
インストールし、JS テンプレートをコピーし、ローカル pod をリンクして
`pod install` を実行します。

`BeautyFilterDemo/` が既に存在する場合、スクリプトは React Native の init を
スキップし、オーバーレイ・依存関係・pod 設定のみを更新します。

## Metro の起動

デバッグビルドでは JavaScript の読み込みに Metro が必要です。

```bash
cd ios/BeautyFilterDemo
npx react-native start
```

アプリ実行中はこのターミナルを起動したままにしてください。

## シミュレータで実行

```bash
cd ios/BeautyFilterDemo
npx react-native run-ios --simulator "iPhone 17"
```

シミュレータは次のテストに有用です。

- アプリ起動。
- 画像ピッカーのフロー。
- 静止画の描画。
- スムージングと美白。
- スライダー UI と React Native ラッパーの挙動。

シミュレータは次のテストには**適しません**。

- カメラ。
- 顔ランドマーク。
- 完全な顔リシェイプ、目の拡大、ランドマークベースのチーク。

## 実機 iPhone で実行

ワークスペースを開きます。

```bash
open ios/BeautyFilterDemo/ios/BeautyFilterDemo.xcworkspace
```

Xcode で:

1. `BeautyFilterDemo` ターゲットを選択します。
2. `Signing & Capabilities` を開きます。
3. Apple Team を選択します。
4. iPhone を接続し、実行先として選択します。
5. Run を押します。

個人の開発者アカウントから初めてインストールする場合、iPhone の設定で開発者
プロファイルを信頼する必要があることがあります。

## デモアプリの使い方

### 画像モード

1. 画像（Image）タブを選択します。
2. 画像ピッカーのボタンをタップします。
3. フォトライブラリからポートレート画像を選択します。
4. スムージング、美白、小顔、目の拡大、チークの各スライダーを調整します。

静止画では、`imageUri` またはフィルタパラメータが変わるたびに、無駄な再描画を
避けるためランループ末尾で 1 回の描画パスがスケジュールされます。

### カメラモード

1. 実機 iPhone でアプリを実行します。
2. カメラ（Camera）タブを選択します。
3. iOS が尋ねたらカメラ権限を許可します。
4. スライダーを調整してリアルタイム効果を更新します。

カメラビューは表示されると自動的に開始し、画面から外れると自動的に停止します。

## React Native でのネイティブコンポーネント利用

ラッパーはブートストラップ後、`BeautyFilterDemo/src/index.ts` で利用できます。

静止画処理:

```tsx
<GPUPixelImageView
  style={{ flex: 1 }}
  imageUri={imageUri}
  smoothing={6}
  whitening={4}
  faceSlim={3}
  eyeEnlarge={3}
  blusher={2}
/>
```

カメラ:

```tsx
<GPUPixelCameraView
  style={{ flex: 1 }}
  smoothing={6}
  whitening={4}
  faceSlim={3}
  eyeEnlarge={3}
  blusher={2}
/>
```

現行のフィルタパラメータはすべて公開範囲 `0-10` を使用します。

## よくある問題

### `Could not connect to localhost:8081` または `No script URL provided`

Metro が起動していないか、アプリが Metro に接続できません。

```bash
cd ios/BeautyFilterDemo
npx react-native start
```

その後、アプリをリロードします。

### CocoaPods が無く `pod install` が失敗する

```bash
brew install cocoapods
cd ios
./bootstrap.sh
```

### シミュレータでカメラが表示されない

これは想定どおりです。`GPUPixelCameraView` は実機 iPhone でのみカメラモードを
実行します。

### シミュレータで小顔・目の拡大・チークが明瞭でない

このSDKパッケージでは MNN/mars バイナリがデバイス専用のため、シミュレータ
ビルドは顔検出を有効化しません。ランドマーク依存の効果は実機 iPhone でテスト
してください。

### デバイスビルドが MNN または mars のリンクで失敗する

次のファイルが存在するか確認します。

```text
prebuilt-sdk/ios/MNN.framework
prebuilt-sdk/ios/libmars-face-kit.a
```

無い場合はビルド済み SDK パッケージを復元してください。

## 新しいマシンへの移行

ソースルートからデモアプリを再生成できます。

```bash
cd ios
./bootstrap.sh
```

`BeautyFilterDemo/node_modules` や `BeautyFilterDemo/ios/Pods` をコピーする必要は
ありません。`prebuilt-sdk/ios/` にはネイティブ SDK のバイナリとリソースが含まれる
ため、必ず保持してください。

# ビルドガイド

iOS アプリは C++ エンジンをビルドしません。エンジンは `BeautyFilter.xcframework`
内の**ビルド済み**バイナリです。ビルドが行うのは、React Native アプリの生成、
ローカル CocoaPod `BeautyFilterSDK` のリンク、ObjC++ ブリッジのコンパイル、
リソースのパッケージングのみです。

## ツールチェーン

| ツール | バージョン／入手元 | 備考 |
|---|---|---|
| macOS + Xcode | 必須 | iOS のコンパイルと実機実行に必要 |
| Node.js | ≥ 18 | React Native CLI |
| React Native CLI | `@react-native-community/cli@latest` | `bootstrap.sh` が `init` を呼ぶ |
| CocoaPods | 最近のもの | ローカル pod のリンク、リソースのコピー |
| C++ 標準 | C++17 | podspec の `CLANG_CXX_LANGUAGE_STANDARD` |
| C++ ライブラリ | libc++ | `CLANG_CXX_LIBRARY` |
| iOS デプロイメントターゲット | `15.1` | `s.platform = :ios, '15.1'` |
| アーキテクチャ | `arm64`（デバイス）, `arm64-simulator` | xcframework の 2 スライス |

## 依存関係

JS/React Native（`bootstrap.sh` がインストール）:

```text
@react-native-community/slider
react-native-image-picker
```

ネイティブ（`BeautyFilterSDK.podspec` 経由）:

- `BeautyFilter.xcframework` — ビルド済み GPUPixel エンジン（2 スライス:
  `ios-arm64`、`ios-arm64-simulator`）。
- `MNN.framework` + `libmars-face-kit.a` — 顔検出、**デバイススライスのみ**。
- `React-Core` — `RCTViewManager`、`RCTBridgeModule` のヘッダ。
- システムフレームワーク: `UIKit`、`AVFoundation`、`CoreVideo`、`CoreMedia`、
  `CoreGraphics`、`QuartzCore`、`OpenGLES`、`Accelerate`。デバイスビルドは
  さらに `CoreML` と `Metal` をリンクします。

## ソース構成

```text
ios/
├── bootstrap.sh                 # RN アプリの生成・更新
├── BeautyFilterSDK.podspec      # ローカル CocoaPod
├── native-bridge/               # ObjC++ ブリッジ (.h/.mm)
├── prebuilt-sdk/ios/            # エンジン + framework + res + models
│   ├── BeautyFilter.xcframework
│   ├── MNN.framework
│   ├── libmars-face-kit.a
│   ├── include/gpupixel/        # ヘッダ
│   ├── res/                     # LUT + メイクテクスチャ
│   └── models/                  # face_det / face_align
├── app-template/                # App.tsx + src/index.ts（オーバーレイ）
└── BeautyFilterDemo/            # bootstrap が生成する RN アプリ（再生成可能）
```

`BeautyFilterDemo/` は生成物であり、**正本ソースではありません**。`bootstrap.sh`
でいつでも再生成できます。

## ブートストラップ

```bash
cd ios
./bootstrap.sh
```

`bootstrap.sh` は次を行います。

1. macOS と `node`/`npx`/`pod`、および `BeautyFilter.xcframework` の存在を確認。
2. `npx @react-native-community/cli init BeautyFilterDemo --skip-install`
   （既存ならスキップ）。
3. `npm install` と `@react-native-community/slider`、
   `react-native-image-picker` のインストール。
4. `app-template/App.tsx` → `BeautyFilterDemo/App.tsx`、
   `app-template/src/index.ts` → `BeautyFilterDemo/src/index.ts` のオーバーレイ。
5. `pod 'BeautyFilterSDK', :path => '../..'` を `BeautyFilterDemo/ios/Podfile`
   に挿入。
6. `NSPhotoLibraryUsageDescription` と `NSCameraUsageDescription` を
   `Info.plist` に追加（PlistBuddy 経由）。
7. `RCT_NEW_ARCH_ENABLED=0 pod install`。

> デモは prop 駆動ブリッジと `requireNativeComponent` を確実に動作させるため、
> **New Architecture を無効化**します（`RCT_NEW_ARCH_ENABLED=0`）。

## Podspec 設定

`BeautyFilterSDK.podspec` は次の 4 つをパッケージ化します。

```ruby
s.source_files        = 'native-bridge/**/*.{h,mm}'                  # 1. ObjC++ ブリッジ
s.vendored_frameworks = 'prebuilt-sdk/ios/BeautyFilter.xcframework'  # 2. エンジン
s.resources           = ['prebuilt-sdk/ios/res', 'prebuilt-sdk/ios/models'] # 3. リソース
s.dependency 'React-Core'
```

### デバイス／シミュレータのスライス分割

最も重要な部分です。顔検出はデバイスでのみ有効化されます。

```ruby
s.pod_target_xcconfig = {
  'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
  'CLANG_CXX_LIBRARY'           => 'libc++',
  'HEADER_SEARCH_PATHS'         => '".../prebuilt-sdk/ios/include"',

  # DEVICE スライス (iphoneos): detector 有効化 + MNN/CoreML/Metal/mars リンク
  'GCC_PREPROCESSOR_DEFINITIONS[sdk=iphoneos*]' => '$(inherited) GPUPIXEL_ENABLE_FACE_DETECTOR=1',
  'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]'       => '".../prebuilt-sdk/ios"',
  'LIBRARY_SEARCH_PATHS[sdk=iphoneos*]'         => '".../prebuilt-sdk/ios"',
  'OTHER_LDFLAGS[sdk=iphoneos*]'                => '$(inherited) -framework MNN -framework CoreML -framework Metal -l"mars-face-kit"',
  # SIMULATOR スライス: マクロ未定義、MNN/mars 未リンク
}
```

| フラグ | スコープ | 意味 |
|---|---|---|
| `GPUPIXEL_ENABLE_FACE_DETECTOR=1` | `iphoneos` | ブリッジの顔検出 `#ifdef` を有効化 |
| `-framework MNN -framework Metal -framework CoreML` | `iphoneos` | MNN バックエンドをリンク |
| `-l"mars-face-kit"` | `iphoneos` | ランドマーク静的ライブラリをリンク |
| （なし） | simulator | detector オフ、スムージング／美白のみ |

## 実行

### Metro（デバッグビルドに必須）

```bash
cd ios/BeautyFilterDemo
npx react-native start
```

### シミュレータ

```bash
cd ios/BeautyFilterDemo
npx react-native run-ios --simulator "iPhone 17"
```

テスト可能: アプリ起動、画像ピッカー、静止画描画、スムージング、美白、
スライダー／ラッパー。**テスト不可**: カメラとランドマーク依存の効果。

### 実機 iPhone

```bash
open ios/BeautyFilterDemo/ios/BeautyFilterDemo.xcworkspace
```

Xcode で: ターゲット `BeautyFilterDemo` → Signing & Capabilities → Team を選択 →
iPhone を接続 → Run。

## 権限と Info.plist

`bootstrap.sh` は次を追加します。

```xml
<key>NSPhotoLibraryUsageDescription</key>
<string>美容処理する画像を選ぶため、フォトライブラリへのアクセスが必要です。</string>
<key>NSCameraUsageDescription</key>
<string>リアルタイム美容モードのためにカメラが必要です。</string>
```

この 2 つのキーが無いと、ピッカー／カメラを開く際に iOS がクラッシュします。

## トラブルシューティング

### `Could not connect to localhost:8081` / `No script URL provided`

Metro が起動していません。`cd ios/BeautyFilterDemo && npx react-native start` の後、
アプリをリロードします。

### CocoaPods が無く `pod install` が失敗する

```bash
brew install cocoapods
cd ios && ./bootstrap.sh
```

### デバイスビルドが MNN/mars のリンクで失敗する

次の存在を確認します。

```text
prebuilt-sdk/ios/MNN.framework
prebuilt-sdk/ios/libmars-face-kit.a
```

無い場合はビルド済み SDK パッケージを復元します。

### シミュレータでカメラが表示されない

想定どおりです。`GPUPixelCameraView.startCamera` はシミュレータでは早期 return
します（`#if TARGET_OS_SIMULATOR`）。実機 iPhone を使用してください。

### シミュレータで小顔／目の拡大／チークが見えない

シミュレータスライスは `GPUPIXEL_ENABLE_FACE_DETECTOR` を定義しません
（MNN/mars はデバイス専用）。ランドマーク効果は実機 iPhone でテストしてください。

### プレビューが黒い／描画されない

- `SinkView` は有効な bounds を必要とします。ビューにサイズがあるか確認します
  （`!CGRectIsEmpty(self.bounds)`）。
- カメラ権限が許可されているか確認します。
- Xcode コンソールで `[GPUPixel]` ログタグを探します。

## 新しいマシンへの移行

```bash
cd ios && ./bootstrap.sh
```

`BeautyFilterDemo/node_modules` や `ios/Pods` をコピーする必要はありません。
バイナリエンジンとリソースを含む `prebuilt-sdk/ios/` は**必ず保持**してください。

# アーキテクチャ

## 概要

iOS アプリは 3 つのレイヤーで構成されます。

- **React Native (TypeScript)**: UI、画像／カメラのモード切替、画像ピッカー、
  5 つのスライダー、prop によるパラメータ受け渡し。
- **ObjC++ ブリッジ** (`native-bridge/*.mm`): React Native と C++ エンジンを
  接続し、`AVCaptureSession` の管理、画像のデコード、ビューのライフサイクルを
  担います。
- **ネイティブ C++ エンジン** (`BeautyFilter.xcframework`): GPUPixel のフィルタ
  グラフ、OpenGL ES レンダリング、顔検出ラッパー。これは**ビルド済みバイナリ**
  であり、iOS リポジトリは C++ コードを再ビルドしません。

統合は **prop 駆動**です。React Native がネイティブビューに prop を設定し、各
セッターが値をエンジンへ転送します。**リードバックはありません** — 静止画と
カメラの双方が、`UIView` 上にホストされた `CAEAGLLayer` である `SinkView` に
直接描画します。

```text
React Native App.tsx (mode: image | camera)
  -> props (smoothing, whitening, faceSlim, eyeEnlarge, blusher, imageUri)
  -> RCTViewManager (RCT_EXPORT_VIEW_PROPERTY)
  -> GPUPixelImageView / GPUPixelCameraView (ObjC++)
  -> SourceRawData (BGRA または RGBA)
  -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter
  -> SinkView (UIView に直接描画、リードバックなし)
```

同一パイプラインを共有する 2 つの処理経路があります。

```text
リアルタイムカメラ:
  AVCaptureSession (フロント, BGRA 1280x720)
  -> シリアルキュー上の captureOutput デリゲート
  -> フレームごとに FaceDetector::Detect(BGRA, VIDEO)
  -> SourceRawData::ProcessData(BGRA)
  -> パイプライン -> SinkView

静止画:
  UIImage -> CGBitmapContext RGBA (向きを焼き込み)
  -> FaceDetector::Detect(RGBA, PICTURE)
  -> SourceRawData::ProcessData(RGBA)
  -> パイプライン -> SinkView
```

## React Native レイヤー

`app-template/App.tsx` の責務:

- `image` と `camera` のモード切替。
- 写真選択のための `launchImageLibrary`（react-native-image-picker）。
- 5 つの `0..10` スライダー: smoothing、whitening、faceSlim、eyeEnlarge、blusher。
- `GPUPixelImageView` または `GPUPixelCameraView` を描画し、パラメータを
  **prop 経由で**渡します。

`app-template/src/index.ts` は 2 つのネイティブコンポーネントを
`requireNativeComponent` で宣言します。

```ts
export const GPUPixelImageView =
  requireNativeComponent<GPUPixelImageViewProps>('GPUPixelImageView');
export const GPUPixelCameraView =
  requireNativeComponent<GPUPixelCameraViewProps>('GPUPixelCameraView');
```

UI は純粋な React Native であり、専用のネイティブ画面はありません。

## ブリッジレイヤー

`native-bridge/` の内容:

| ファイル | 役割 |
|---|---|
| `GPUPixelImageView.{h,mm}` | 静止画処理用ネイティブビュー |
| `GPUPixelImageViewManager.{h,mm}` | `GPUPixelImageView` と prop を RN へ公開 |
| `GPUPixelCameraView.{h,mm}` | リアルタイムカメラ用ネイティブビュー |
| `GPUPixelViewManager.{h,mm}` | `GPUPixelCameraView` と prop を RN へ公開 |
| `GPUPixelModule.{h,mm}` | 命令型ネイティブモジュール（旧来）: `viewRegistry` 経由のカメラ開始／停止とセッター |

`GPUPixelModule` は旧来の命令型制御経路です。現行のデモは **prop 駆動**方式を
優先します。New Architecture では、`RCTUIManager viewRegistry` 経由の参照が
Fabric/interop ビューに対して信頼性に欠けるためです。[api-bridge](#ブリッジ-api-リファレンス)
を参照してください。

## ネイティブレイヤー

エンジンは `prebuilt-sdk/ios/BeautyFilter.xcframework` にあります。各 ObjC++
ビューは `std::shared_ptr` でパイプラインをローカルに保持します。

```cpp
std::shared_ptr<SourceRawData>     _source;
std::shared_ptr<SinkView>          _sinkView;
std::shared_ptr<BeautyFaceFilter>  _beauty;
std::shared_ptr<FaceReshapeFilter> _reshape;
std::shared_ptr<BlusherFilter>     _blusher;
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
std::shared_ptr<FaceDetector>      _faceDetector;
#endif
```

パイプラインは**ビューインスタンスごと**に保持されます。各 `GPUPixelCameraView`
と `GPUPixelImageView` は独自のグラフを持ち、共有のグローバルエンジン状態は
ありません。

`GPUPIXEL_ENABLE_FACE_DETECTOR=1`（デバイスビルド）のときのグラフ:

```text
SourceRawData -> BlusherFilter -> FaceReshapeFilter -> BeautyFaceFilter -> SinkView
```

detector がオフ（シミュレータビルド）のとき、コードは `_blusher` と `_reshape`
を生成しますが、ランドマークが無いためパススルーします。`BeautyFaceFilter` の
スムージング／美白のみが効果を発揮します。

## スレッドモデル

ネイティブアクセスは単一のエグゼキュータでシリアル化されません。代わりに:

- **カメラフレーム**はシリアルキュー `com.gpupixel.camera` 上で動作します
  （`setSampleBufferDelegate:queue:` で設定）。
- **静止画の再処理**は `dispatch_async(dispatch_get_main_queue())` で
  スケジュールされ、複数の prop 変更をランループ末尾の 1 回の描画パスに
  まとめます。
- **パラメータセッター**は任意のスレッド（RN UIManager スレッド）から呼び出せ、
  `if (_beauty) ...` のような null チェックのみで保護されます。

GPUPixel は独自の GL コンテキストを管理し、各ビューが独立したパイプラインを
持つため、グローバルロックはありません。カメラがフレームを処理している最中に
スライダーが多数の変更を発火させる場合は、依然としてスレッドセーフティに注意が
必要です（[ios-integration-guide](#ios-統合ガイド) のハードニングを参照）。

## カメラパイプライン

`AVCaptureSession` の主要設定（`GPUPixelCameraView._setupCaptureSession`）:

- `AVCaptureSessionPreset1280x720`
- フロントカメラ: `AVCaptureDeviceTypeBuiltInWideAngleCamera`、
  `AVCaptureDevicePositionFront`
- 出力フォーマット: `kCVPixelFormatType_32BGRA`
- `alwaysDiscardsLateVideoFrames = YES`（パイプラインが忙しいとき遅延フレームを
  破棄）
- コネクション: `videoOrientation = Portrait`、`videoMirrored = YES`

プレビュー経路:

1. `captureOutput:didOutputSampleBuffer:` が `CMSampleBufferRef` を受け取ります。
2. `CVImageBufferRef` をロックし、`baseAddress`、`width`、`height`、`stride` を
   読み取ります。
3. detector があれば、`FaceDetector::Detect(BGRA, VIDEO)` がランドマークを生成し、
   `_blusher` と `_reshape` へ渡します。
4. `SourceRawData::ProcessData(baseAddress, w, h, stride, GPUPIXEL_FRAME_TYPE_BGRA)`。
5. パイプラインが `SinkView` へ描画します。
6. バッファをアンロックします。

`glReadPixels` も libyuv の再パックもありません。カメラは既に BGRA を生成し、
シェーダが GPU 上で RGB に変換します。

## 回転とミラーリング

回転とミラーリングは AVFoundation が処理します。

```objc
conn.videoOrientation = AVCaptureVideoOrientationPortrait;
conn.videoMirrored = YES;  // セルフィーミラー
```

フレームはデリゲートに正しい向きで届くため、`SourceRawData` は既定の
`NoRotation` を使用します。

静止画では、`CGBitmapContext` で `drawInRect` の前に Y 軸を反転して、EXIF の
向きを**デコード時に焼き込みます**（[api-bridge](#ブリッジ-api-リファレンス) の
画像デコードを参照）。

## 顔検出戦略

現行実装は**フルレゾリューションの BGRA バッファ上で毎フレーム**検出します。

```cpp
std::vector<float> landmarks = _faceDetector->Detect(
    baseAddress, width, height, stride,
    GPUPIXEL_MODE_FMT_VIDEO, GPUPIXEL_FRAME_TYPE_BGRA);
```

検出のスロットリングもダウンスケールも無いため、これは FPS 最適化の最有力候補
です（[ios-integration-guide](#ios-統合ガイド) を参照）。静止画は
`GPUPIXEL_MODE_FMT_PICTURE` を使用します。

ランドマークは入力画像空間で `[0,1]` に正規化され、出力へ直接適用されます。

## アセット

リソースは実行時に手動でコピーされません。CocoaPods がビルド時に `res/` と
`models/` を**アプリバンドル**へコピーします。

```ruby
s.resources = ['prebuilt-sdk/ios/res', 'prebuilt-sdk/ios/models']
```

ネイティブコードはバンドル経由でリソースを解決します。

```objc
NSString* resPath = [[NSBundle mainBundle] resourcePath];
GPUPixel::SetResourceRoot([resPath UTF8String]);
```

[assets-and-textures](#アセットとテクスチャ) を参照してください。

## シミュレータ対デバイス

| 機能 | シミュレータ | 実機 iPhone |
|---|---|---|
| 肌のスムージング | あり | あり |
| 美白 | あり | あり |
| 顔ランドマーク | なし | あり |
| 小顔／目の拡大／チーク | なし | あり |
| リアルタイムカメラ | なし | あり |

理由: `MNN.framework` と `libmars-face-kit.a` はデバイス（`iphoneos`）スライス
のみを提供します。シミュレータスライスは `GPUPIXEL_ENABLE_FACE_DETECTOR` を
オフにしてビルドされます。

## パフォーマンスメモ

現行の最適化:

- カメラ入力はネイティブ BGRA のままで、色変換は GPU シェーダで行われます。
- 両モードとも `SinkView`（`CAEAGLLayer`）へ直接描画し、**リードバックなし**。
- `alwaysDiscardsLateVideoFrames` がパイプライン混雑時に遅延フレームを破棄。
- 静止画描画はランループ末尾で**再処理をまとめ**、複数スライダー操作中の冗長な
  描画を回避します。
- パイプラインはビューごとで、共有のグローバル GL 状態はありません。

改善余地: 顔検出が**毎フレーム・フルレゾリューション**で動作しています。検出の
スロットリングやダウンスケールが、FPS 向上の最初の候補です。

# ブリッジ API リファレンス

React Native は **ObjC++ ブリッジ**を介して 2 つの仕組みで C++ エンジンと通信
します。

- **prop 駆動（推奨）:** `RCTViewManager` がネイティブビューの prop を公開し、
  RN が prop を設定すると ObjC++ のセッターが動作して値がエンジンへ届きます。
- **命令型（旧来）:** `GPUPixelModule`（`RCTBridgeModule`）が `viewRegistry`
  経由でメソッドを呼びます。New Architecture では信頼性が低めです。

| コンポーネント | ファイル | 役割 |
|---|---|---|
| `GPUPixelImageView` | `native-bridge/GPUPixelImageView.mm` | 静止画ビュー |
| `GPUPixelImageViewManager` | `native-bridge/GPUPixelImageViewManager.mm` | ビュー + prop を公開（`GPUPixelImageView`） |
| `GPUPixelCameraView` | `native-bridge/GPUPixelCameraView.mm` | リアルタイムカメラビュー |
| `GPUPixelViewManager` | `native-bridge/GPUPixelViewManager.mm` | ビュー + prop を公開（`GPUPixelCameraView`） |
| `GPUPixelModule` | `native-bridge/GPUPixelModule.mm` | 命令型ネイティブモジュール（旧来） |

## TypeScript サーフェス

`app-template/src/index.ts`:

```ts
export interface FilterParamProps {
  smoothing?: number;  // 0 – 10
  whitening?: number;  // 0 – 10
  faceSlim?: number;   // 0 – 10
  eyeEnlarge?: number; // 0 – 10
  blusher?: number;    // 0 – 10
}

export interface GPUPixelImageViewProps extends FilterParamProps {
  style?: ViewStyle;
  imageUri?: string;   // file:// または絶対パス
}

export interface GPUPixelCameraViewProps extends FilterParamProps {
  style?: ViewStyle;
}

export const GPUPixelImageView =
  requireNativeComponent<GPUPixelImageViewProps>('GPUPixelImageView');
export const GPUPixelCameraView =
  requireNativeComponent<GPUPixelCameraViewProps>('GPUPixelCameraView');
```

`'GPUPixelImageView'` と `'GPUPixelCameraView'` という名前は、ビューマネージャの
`RCT_EXPORT_MODULE(...)` と一致します。

## Prop API — GPUPixelImageView

マネージャ（`GPUPixelImageViewManager.mm`）が公開する内容:

```objc
RCT_EXPORT_MODULE(GPUPixelImageView)
RCT_EXPORT_VIEW_PROPERTY(imageUri, NSString)
RCT_EXPORT_VIEW_PROPERTY(smoothing, float)
RCT_EXPORT_VIEW_PROPERTY(whitening, float)
RCT_EXPORT_VIEW_PROPERTY(faceSlim, float)
RCT_EXPORT_VIEW_PROPERTY(eyeEnlarge, float)
RCT_EXPORT_VIEW_PROPERTY(blusher, float)
```

各 prop はビュー上の同名セッターにマッピングされます。すべてのセッターは
`_scheduleReprocess` を呼び、変更をランループ末尾の 1 回の描画パスにまとめます。

```objc
- (void)setImageUri:(NSString*)uri { [self _loadImage:uri]; [self _scheduleReprocess]; }
- (void)setSmoothing:(float)value  { _smoothing = value;  [self _scheduleReprocess]; }
// ... whitening / faceSlim / eyeEnlarge / blusher も同様
```

### 画像デコード — `_loadImage:`

1. `file://` 接頭辞を取り除いてパスを取得します。
2. `[UIImage imageWithContentsOfFile:path]`。
3. ピクセルサイズを計算: `width * scale`、`height * scale`。
4. `CGBitmapContext` RGBA に描画
   （`kCGImageAlphaPremultipliedLast | kCGBitmapByteOrder32Big`）。
5. **向きの焼き込み:** `drawInRect` の前に Y 軸を反転
   （`TranslateCTM(0,h)` + `ScaleCTM(1,-1)`）し、正しい EXIF 向きの top-left
   origin フレームを生成します。
6. `std::vector<uint8_t> _rgba` に格納し、`_imgWidth/_imgHeight` を設定して
   `_hasImage = YES` にします。

### 再処理 — `_reprocess`

```objc
[self _ensurePipeline];                       // 必要ならグラフ生成。有効な bounds が必要
if (!_pipelineReady || !_hasImage) return;
[self _applyParams];
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  _landmarks = _faceDetector->Detect(data, _imgWidth, _imgHeight, _imgWidth*4,
                                     GPUPIXEL_MODE_FMT_PICTURE, GPUPIXEL_FRAME_TYPE_RGBA);
  _blusher->SetFaceLandmarks(_landmarks);
  _reshape->SetFaceLandmarks(_landmarks);
#endif
_source->ProcessData(data, _imgWidth, _imgHeight, _imgWidth*4, GPUPIXEL_FRAME_TYPE_RGBA);
```

`_scheduleReprocess` は `_reprocessScheduled` フラグで保護された
`dispatch_async(main_queue)` を使用し、複数の prop 変更をちょうど 1 回の描画に
まとめます。

## Prop API — GPUPixelCameraView

マネージャ（`GPUPixelViewManager.mm`）が公開する内容:

```objc
RCT_EXPORT_MODULE(GPUPixelCameraView)
RCT_EXPORT_VIEW_PROPERTY(smoothing, float)
RCT_EXPORT_VIEW_PROPERTY(whitening, float)
RCT_EXPORT_VIEW_PROPERTY(faceSlim, float)
RCT_EXPORT_VIEW_PROPERTY(eyeEnlarge, float)
RCT_EXPORT_VIEW_PROPERTY(blusher, float)
```

カメラのセッターはエンジンへ**即時**適用され（まとめなし）、パイプライン準備後に
再適用できるよう値をキャッシュします。

```objc
- (void)setSmoothing:(float)value {
  _smoothing = value;
  if (_beauty) _beauty->SetBlurAlpha(value / 10.0f);
}
```

### 自動ライフサイクル

カメラはビューのライフサイクルに合わせて**自動的に開始・停止**します。JS が
メソッドを呼ぶ必要はありません。

```objc
- (void)didMoveToWindow {
  [super didMoveToWindow];
  if (self.window) [self _maybeStartCamera];
  else             [self stopCamera];
}
- (void)layoutSubviews { [super layoutSubviews]; [self _maybeStartCamera]; }

- (void)_maybeStartCamera {
  if (_pipelineReady || _starting) return;
  if (!self.window) return;
  if (CGRectIsEmpty(self.bounds)) return;  // SinkView は有効な bounds が必要
  [self startCamera];
}
```

`startCamera` はカメラ権限を要求し
（`AVCaptureDevice requestAccessForMediaType:`）、その後パイプラインと
`AVCaptureSession` をセットアップします。`stopCamera` はセッションを停止し、
すべての shared_ptr を `.reset()` します。

> シミュレータではカメラが無いため、`startCamera` は早期 return します
> （`#if TARGET_OS_SIMULATOR`）。

### カメラフレームコールバック

```objc
- (void)captureOutput:(AVCaptureOutput*)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection*)connection {
  if (!_pipelineReady) return;
  CVImageBufferRef pb = CMSampleBufferGetImageBuffer(sampleBuffer);
  CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
  // baseAddress / width / height / stride
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  landmarks = _faceDetector->Detect(base, w, h, stride,
                                    GPUPIXEL_MODE_FMT_VIDEO, GPUPIXEL_FRAME_TYPE_BGRA);
  _blusher->SetFaceLandmarks(landmarks);
  _reshape->SetFaceLandmarks(landmarks);
#endif
  _source->ProcessData(base, w, h, stride, GPUPIXEL_FRAME_TYPE_BGRA);
  CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
}
```

これはシリアルキュー `com.gpupixel.camera` 上で動作します。検出のスロットリング
もダウンスケールもありません。

## 命令型 API — GPUPixelModule（旧来）

`GPUPixelModule.mm` は `methodQueue = main_queue` の `RCTBridgeModule` を公開
します。各メソッドは `RCTUIManager viewRegistry[reactTag]` 経由でビューを参照
します。

| メソッド | パラメータ | 効果 |
|---|---|---|
| `startCamera(reactTag)` | view tag | `[view startCamera]` を呼ぶ |
| `stopCamera(reactTag)` | view tag | `[view stopCamera]` を呼ぶ |
| `setBeautyParams(reactTag, smoothing, whitening)` | 0..10 | `setSmoothing` + `setWhitening` |
| `setReshapeParams(reactTag, faceSlim, eyeEnlarge)` | 0..10 | `setFaceSlim` + `setEyeEnlarge` |
| `setMakeupParams(reactTag, blusher)` | 0..10 | `setBlusher` |

> New Architecture では**非推奨**です。`viewRegistry` は Fabric/interop ビューを
> 確実に保持しないため、`viewRegistry[reactTag]` が nil を返すことがあります。
> prop 駆動 API を使用してください。

## パラメータマッピング

公開 UI 範囲は `0..10` で、エンジンへ届く前にスケールダウンされます。

| Prop | UI 範囲 | ネイティブ変換 | 対象 |
|---|---:|---|---|
| `smoothing` | `0..10` | `value / 10.0` | `BeautyFaceFilter::SetBlurAlpha` |
| `whitening` | `0..10` | `value / 20.0` | `BeautyFaceFilter::SetWhite` |
| `faceSlim` | `0..10` | `value / 200.0` | `FaceReshapeFilter::SetFaceSlimLevel` |
| `eyeEnlarge` | `0..10` | `value / 100.0` | `FaceReshapeFilter::SetEyeZoomLevel` |
| `blusher` | `0..10` | `value / 10.0` | `BlusherFilter::SetBlendLevel` |

`faceSlim`、`eyeEnlarge`、`blusher` はランドマークが利用可能なとき
（デバイスビルド）のみ効果を発揮します。`smoothing` と `whitening` は
シミュレータでも動作します。

## エラーハンドリング

ブリッジは例外を投げず、`[GPUPixel]` 接頭辞付きで `NSLog` に記録します。

- ユーザーによるカメラ権限の拒否: `"Người dùng từ chối quyền camera."`
- 権限が denied/restricted: `"Quyền camera bị từ chối/giới hạn..."`
- フロントカメラ無し（通常はシミュレータ）: `"Không tìm thấy camera trước..."`

これを本番化する（`onReady` / `onError` / `onCameraPermissionDenied` などの
イベントコールバック）には、[ios-integration-guide](#ios-統合ガイド) を参照して
ください。

# フィルタパイプライン

ネイティブパイプラインは有向グラフです。

```text
Source -> Filter -> Filter -> ... -> Sink
```

各フィルタは前ノードからテクスチャを受け取り、シェーダを新しいフレームバッファ
へ描画して、次のシンクへ渡します。エンジンは `BeautyFilter.xcframework` 内の
**ビルド済み GPUPixel** ライブラリです。

## メイングラフ

`GPUPixelImageView` と `GPUPixelCameraView` の双方が、`_setupGPUPixelPipeline` /
`_ensurePipeline` で**同一に**グラフを構築します。

```text
SourceRawData
  -> BlusherFilter
  -> FaceReshapeFilter
  -> BeautyFaceFilter
  -> SinkView
```

```cpp
_source->AddSink(_blusher);
_blusher->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

終端フィルタは常に `BeautyFaceFilter` で、その後に `SinkView` が続きます。iOS
デモは静止画とカメラの両方で常に `SinkView` へ描画します。結果を表示するのみで、
キャプチャ／保存機能はありません。

detector がオフ（シミュレータ）のとき、`_blusher` と `_reshape` はグラフに
残りますが、ランドマークが無いためパススルーします。`BeautyFaceFilter` の
スムージングと美白のみが効果を発揮します。

## SourceRawData

**ヘッダ:** `prebuilt-sdk/ios/include/gpupixel/source/source_raw_data.h`

ブリッジからのフレームを単一の API で受け取ります。

```cpp
void ProcessData(const uint8_t* data, int width, int height,
                 int stride, GPUPIXEL_FRAME_TYPE type);
```

iOS アプリで使用する 2 つのフォーマット:

```text
カメラ:  GPUPIXEL_FRAME_TYPE_BGRA   (32BGRA の CVPixelBuffer から)
画像:    GPUPIXEL_FRAME_TYPE_RGBA   (CGBitmapContext から)
```

BGRA 経路:

```text
CVPixelBuffer 32BGRA (1280x720)
-> CVPixelBufferGetBaseAddress + stride
-> SourceRawData::ProcessData(..., BGRA)
-> シェーダが BGRA を RGB に変換
-> NoRotation (AVFoundation が既に回転/ミラー)
```

RGBA 経路:

```text
UIImage -> CGBitmapContext RGBA (top-left, 向きを焼き込み)
-> SourceRawData::ProcessData(..., RGBA)
-> NoRotation
```

`SourceRawData` は寸法が変わらない限り GL テクスチャ／フレームバッファを
再利用します。

## BlusherFilter

**ヘッダ:** `prebuilt-sdk/ios/include/gpupixel/filter/blusher_filter.h`
**基底:** `FaceMakeupFilter`
**要件:** 有効なランドマーク（デバイスのみ）

`FaceMakeupFilter` の三角形メッシュを用いて、`res/blusher.png` を頬領域へ
オーバーレイします。

ブリッジマッピング:

```cpp
_blusher->SetBlendLevel(value / 10.0f);  // value: 0..10
```

顔が無い、またはランドマークが不足している場合、フィルタはパススルーします。

## FaceReshapeFilter

**ヘッダ:** `prebuilt-sdk/ios/include/gpupixel/filter/face_reshape_filter.h`
**要件:** 約 106 個のランドマーク点（デバイスのみ）

2 つの効果:

- 小顔: `curveWarp` が輪郭領域を内側へ引き込みます。
- 目の拡大: `enlargeEye` が両目の中心周りで作用します。

ブリッジマッピング:

```cpp
_reshape->SetFaceSlimLevel(value / 200.0f);  // faceSlim 0..10
_reshape->SetEyeZoomLevel(value / 100.0f);   // eyeEnlarge 0..10
```

ワーピングは UV 空間 `[0,1]` で計算されます。デバイス（detector が必要）でのみ
効果があります。

## BeautyFaceFilter

**ヘッダ:** `prebuilt-sdk/ios/include/gpupixel/filter/beauty_face_filter.h`
**種別:** `FilterGroup`

このフィルタはランドマークを**必要としない**ため、シミュレータでも動作します。

内部グラフ:

```text
input
  -> BilateralFilter -----------\
  -> BoxHighPassFilter ---------+-> BeautyFaceUnitFilter -> output
  -> original ------------------/
```

### BilateralFilter

色距離に基づくエッジ保存ブラーの 2 パス（水平／垂直）。ランドマーク不要。

### BoxHighPassFilter

`original - boxblur(original)` からディテール／分散マップを生成します。どの領域を
スムージングすべきかをシェーダが判断するのに役立ちます。

### BeautyFaceUnitFilter

**ヘッダ:** `prebuilt-sdk/ios/include/gpupixel/filter/beauty_face_unit_filter.h`

1. 肌のスムージング:
   - エッジを保持するためのエッジ検出
   - RGB ベースの肌ヒューリスティック
   - オリジナルとバイラテラル出力をブレンド
   - ハイパスマップからディテールを保持／シャープ化

2. 美白: LUT チェーン `lookup_gray.png` -> `lookup_origin.png` ->
   `lookup_skin.png` -> `lookup_light.png`（uniform `lookUpCustom`）を、`whiten`
   uniform でブレンドします。

ブリッジマッピング:

```cpp
_beauty->SetBlurAlpha(value / 10.0f);  // smoothing 0..10
_beauty->SetWhite(value / 20.0f);      // whitening 0..10
```

iOS ブリッジは**常に** `SetWhite(value / 20.0f)` を呼ぶため、美白スライダーを 0 に
戻すと美白が正しくリセットされます。

## SinkView

**ヘッダ:** `prebuilt-sdk/ios/include/gpupixel/sink/sink_view.h`

```cpp
static std::shared_ptr<SinkView> Create(void* parent_view);
```

`parent_view` は `UIView` そのものです（ブリッジが `(__bridge void*)self` を
渡します）。`SinkView` はビュー上に GL レイヤー（`CAEAGLLayer`）を生成し、終端
テクスチャを**画面へ直接**描画します。

このため `SinkView` は生成前に有効な bounds を必要とします。ブリッジは
`layoutSubviews` / `didMoveToWindow` を待ってから `_ensurePipeline` を呼びます。

```objc
if (CGRectIsEmpty(self.bounds)) return;  // SinkView は bounds が必要
```

デモには `SinkRawData` / `glReadPixels` 経路は**ありません**。CPU リードバックも
ファイル出力もありません。両モードとも画面へ直接描画します。

## Face Detector

**ヘッダ:** `prebuilt-sdk/ios/include/gpupixel/face_detector/face_detector.h`
**バックエンド:** `MNN.framework` + `libmars-face-kit.a`（デバイススライスのみ）

```cpp
std::vector<float> Detect(const uint8_t* data, int width, int height,
                          int stride, GPUPIXEL_MODE_FMT fmt,
                          GPUPIXEL_FRAME_TYPE type);
```

カメラは `GPUPIXEL_MODE_FMT_VIDEO` + `GPUPIXEL_FRAME_TYPE_BGRA` を使用します。
静止画は `GPUPIXEL_MODE_FMT_PICTURE` + `GPUPIXEL_FRAME_TYPE_RGBA` を使用します。

返されたランドマークは `BlusherFilter` と `FaceReshapeFilter` へ渡されます。
`BeautyFaceFilter` はランドマークを必要としません。

検出コードは `#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR` で囲まれているため、この
マクロを定義しないシミュレータスライスでは完全にコンパイル除外されます。

## フィルタの追加

[extending-filters](#フィルタの拡張) を参照してください。要点: 終端が
`BeautyFaceFilter -> SinkView` で、グラフが `GPUPixelImageView.mm` と
`GPUPixelCameraView.mm` の**両方**で構築されるため、新しいフィルタは静止画と
カメラの双方へ適用するには**両ファイル**に追加する必要があります。

# 顔ランドマーク — 座標系

## 概要

顔検出は **mars-face-kit**（`libmars-face-kit.a` + `MNN.framework` にパッケージ化、
デバイススライスのみ）を使用します。`FaceDetector::Detect()` は各キーポイント
`(x, y)` を入力画像空間で `[0, 1]` に正規化します（top-left origin、x は右、y は
下方向）。

ObjC++ ブリッジは各ビュー内で detector を直接呼び出します。

```cpp
std::vector<float> landmarks = _faceDetector->Detect(
    data, width, height, stride, fmt, type);
```

ここで:

- **カメラ**（`GPUPixelCameraView`）: `fmt = GPUPIXEL_MODE_FMT_VIDEO`、
  `type = GPUPIXEL_FRAME_TYPE_BGRA`。
- **静止画**（`GPUPixelImageView`）: `fmt = GPUPIXEL_MODE_FMT_PICTURE`、
  `type = GPUPIXEL_FRAME_TYPE_RGBA`。

ランドマークはフィルタへ渡されます。

```cpp
if (_blusher) _blusher->SetFaceLandmarks(landmarks);
if (_reshape) _reshape->SetFaceLandmarks(landmarks);
```

このブロック全体は `#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR` 内にあるため、
**シミュレータにはランドマークがありません**（マクロがシミュレータスライス
では定義されません）。

## 顔領域とインデックス範囲（mars-face-kit 標準）

```
        Forehead
    ┌──────────────┐
    │  17 ─── 26   │  ← Eyebrows: 17–21 (左), 22–26 (右)
    │  36  38  45  │  ← Eyes: 36–41 (左), 42–47 (右)
    │    30─33     │  ← Nose bridge: 27–30, nostrils: 31–35
    │  48─────54   │  ← Mouth outer: 48–59
    │  60─────67   │  ← Mouth inner: 60–67
    │    8         │  ← Chin tip: 8
    └──┐         ┌─┘
       0─────────16   ← Jawline: 0–16 (17 点)
```

| インデックス範囲 | 顔領域 | 備考 |
|---|---|---|
| 0 – 16 | あご輪郭（Jawline） | 17 点, 0=左, 16=右, 8=あご先端 |
| 17 – 21 | 左眉 | 外側 → 内側 |
| 22 – 26 | 右眉 | 内側 → 外側 |
| 27 – 30 | 鼻筋 | 上から下 |
| 31 – 35 | 鼻先 + 鼻孔 | 31=先端 |
| 36 – 41 | 左目 | 36=外側, 39=内側 |
| 42 – 47 | 右目 | 42=内側, 45=外側 |
| 48 – 59 | 口・外唇 | 48=左, 54=右 |
| 60 – 67 | 口・内唇 | |
| 68 – 75 | 瞳孔 + 目の中心 | |
| 76 – 83 | 虹彩の点 | |
| 84 – 105 | 追加輪郭 | 頬、追加点 |

> **注:** mars-face-kit はクローズドソースです。上記のマッピングは
> `FaceReshapeFilter` と `FaceMakeupFilter` の挙動から推測したものです。
> `FaceReshapeFilter` は少なくとも 106 点、`FaceMakeupFilter` はメッシュ
> インデックスを 110 まで使うため少なくとも 111 点が必要です。

## コードで使用されるインデックス

エンジンは次のシェーダインデックスをハードコードしています。

### FaceReshapeFilter — thinFace

```cpp
// シェーダにハードコードされた 9 組の origin -> target:
(3 -> 44), (29 -> 44), (7 -> 45), (25 -> 45), (10 -> 46),
(22 -> 46), (14 -> 49), (18 -> 49), (16 -> 49)
```

### FaceReshapeFilter — bigEye

```cpp
enlargeEye(center=landmarks[74], radiusAnchor=landmarks[72], bigEyeDelta); // 左目
enlargeEye(center=landmarks[77], radiusAnchor=landmarks[75], bigEyeDelta); // 右目
```

### FaceMakeupFilter — 三角形メッシュ

眉、目、頬（`BlusherFilter` 領域）、鼻、唇、あごを覆う 111 点（0–110）の三角形
メッシュです。三角形インデックスはエンジンの `face_makeup_filter.cc` に
ハードコードされています。

## 座標変換

### mars-face-kit 出力 → フィルタ入力

```
[0, 1] 正規化  →  SetFaceLandmarks() へ渡すときも [0, 1] のまま
```

### FaceMakeupFilter — クリップ空間

```cpp
float clipX = landmark.x * 2.0f - 1.0f;  // [0,1] -> [-1,1]
float clipY = landmark.y * 2.0f - 1.0f;
```

### FaceReshapeFilter — UV 空間

```cpp
float dist = distance(uv, anchorPoint);
vec2 warpedUV = uv + direction * falloff(dist) * magnitude;
```

## iOS の Detector 入力

| 項目 | 値 |
|---|---|
| 入力フォーマット | BGRA（カメラ）/ RGBA（画像） |
| 検出サイズ | フルレゾリューションフレーム |
| スロットル | 毎フレーム |
| 向き | AVFoundation が回転/ミラー（カメラ）、EXIF 焼き込み（画像） |
| Detect 呼び出し場所 | 各 ObjC++ ビュー内 |

検出は現在、毎フレーム・フルレゾリューションで動作します。スロットルやダウン
スケールの追加が、FPS 向上の最初の候補です（[architecture](#アーキテクチャ) と
[ios-integration-guide](#ios-統合ガイド) を参照）。

## デバッグ: ランドマークを JS へ公開

`Detect()` はビュー内に保持される `std::vector<float>`
（`GPUPixelImageView` の `_landmarks`）を返し、JS へは送りません。React Native で
デバッグ／オーバーレイするには:

1. `RCTBubblingEventBlock` / `RCTDirectEventBlock` を用いて、ビューマネージャに
   `onFaceDetected` イベントを追加します。
2. detect コールバック内で `std::vector<float>` を `NSArray` に変換してイベントを
   発火します。
3. JS で `event.nativeEvent.landmarks`（`[0,1]` 正規化された x,y ペアの配列）を
   読み取り、`<Svg>` または絶対配置の `<View>` でオーバーレイを描画します。

連続する値が `(x, y)` ペアを構成します。ビューサイズを掛けるとピクセルになります。

# アセットとテクスチャ

iOS はアセットを手動でコピーしません。CocoaPods がビルド時に `res/` と
`models/` を**アプリバンドル**へコピーします。

```ruby
# BeautyFilterSDK.podspec
s.resources = ['prebuilt-sdk/ios/res', 'prebuilt-sdk/ios/models']
```

ネイティブコードはバンドルの resource path 経由でリソースを解決します。

```objc
NSString* resPath = [[NSBundle mainBundle] resourcePath];
GPUPixel::SetResourceRoot([resPath UTF8String]);
```

エンジンは `resPath` からの相対パス（`res/...`、`models/...`）でテクスチャ／
モデルをロードします。

> CocoaPods は copy-resources スクリプトでファイルをそのままコピーします
> （**PNG 圧縮なし**）。そのため LUT の `.png` は破損しません。これが `res/` と
> `models/` を名前空間化せずバンドルルートに置くべき理由です。

```
prebuilt-sdk/ios/
  res/
    blusher.png
    lookup_custom.png
    lookup_gray.png
    lookup_light.png
    lookup_origin.png
    lookup_skin.png
    mouth.png
  models/
    face_det.mars_model
    face_align.mars_model
```

---

## LUT テクスチャ（美白パイプライン）

`BeautyFaceUnitFilter` の美白は LUT チェーンで、適用順は次のとおりです。

```
元のピクセル色
      │
      ▼ Step 1
lookup_gray.png     (1D ストリップ LUT)
      │
      ▼ Step 2
lookup_origin.png   (4×4×16 3D LUT)
      │
      ▼ Step 3
lookup_skin.png     (4×4×16 3D LUT)
      │
      ▼ Step 4 (uniform lookUpCustom)
lookup_light.png    (512×512 → 8×8×64 3D LUT をエンコード, lookUpCustom にバインド)
      │
      ▼
美白後の色 ('whiten' uniform で元色とブレンド)
```

### lookup_gray.png

| プロパティ | 値 |
|---|---|
| フォーマット | 1D カラーストリップ（グレースケールマッピング） |
| 目的 | 3D LUT へ入る前に輝度を調整 |

### lookup_origin.png + lookup_skin.png

| プロパティ | 値 |
|---|---|
| フォーマット | 4×4×16 3D LUT（2D グリッドとしてエンコード） |
| 目的 | 元の肌色から目標の肌色へカラーグレーディング |

### lookup_custom.png

| プロパティ | 値 |
|---|---|
| サイズ | 512×512 px |
| フォーマット | 8×8×64 3D LUT をエンコード |
| 目的 | バンドルされたリソース。シェーダは現在このファイルを**バインドしません** |

### lookup_light.png

シェーダ内で `lookUpCustom` uniform を通して使用されます。美白チェーンの最終
LUT です。`lookup_custom.png` はバンドルに存在しますが、バインドされません。

---

## 新しい LUT の作成

### ツール

- **Photoshop** + "Export Color Lookup Tables" プラグイン（.cube → PNG）
- **DaVinci Resolve** — .cube をエクスポート
- **Python + numpy** — プログラムで生成

### 4×4×16 3D LUT フォーマット（lookup_origin, lookup_skin）

```
Width = 4 * 16 = 64 px
Height = 4 px
各 4×4 "セル" が B スライス。16 スライスを水平に配置
```

GLSL サンプリング:

```glsl
vec3 sampleLUT3D_4x4x16(sampler2D lut, vec3 color) {
    float blueIdx = color.b * 15.0;
    float blueFloor = floor(blueIdx);
    vec2 uvFloor = vec2(
        (blueFloor * 4.0 + color.r * 3.0) / 63.0,
        color.g * 3.0 / 3.0
    );
    // ... bilinear + trilinear 補間
}
```

### iOS での LUT 更新

リソースはアプリバンドル（読み取り専用、ビルド時にコピー）に存在するため、
**実行時ホットリロードはできません**。LUT を変更するには:

1. `prebuilt-sdk/ios/res/` のファイルを置き換えます。
2. `pod install` を再実行（またはクリーンビルド）して、CocoaPods にバンドルへ
   再コピーさせます。
3. アプリを再ビルドします。

---

## メイクテクスチャ

### blusher.png

| プロパティ | 値 |
|---|---|
| 目的 | 頬領域へのチークオーバーレイ |
| テクスチャ領域 | `{left=395, top=520, width=489, height=209}`（ピクセル座標） |
| ブレンドモード | Multiply、`FaceMakeupFilter::DoRender()` にハードコード |
| アルファ | `blusher` prop で制御（0..10 → `/10`） |

**チークテクスチャの置き換え:**
1. アルファチャンネル付き PNG を作成し、頬のアートを領域 `{395, 520, 489, 209}`
   内に配置します。
2. `prebuilt-sdk/ios/res/blusher.png` を置き換えます。
3. `pod install` を実行して再ビルドします。

テクスチャ領域（`SetTextureBounds`）はビルド済みエンジン（`blusher_filter.cc`）
内にあり、エンジンが既にビルド済みのため iOS 側からは**変更できません**。領域を
変更するには GPUPixel ソースから xcframework を再ビルドする必要があります。

### mouth.png

リップスティックテクスチャです。`LipstickFilter` はエンジン／ヘッダに存在
しますが、iOS ブリッジには**まだ組み込まれていません**（`GPUPixelImageView.mm`
/ `GPUPixelCameraView.mm` で生成されていません）。[extending-filters](#フィルタの拡張)
を参照してください。

---

## モデルファイル

### face_det.mars_model + face_align.mars_model

| プロパティ | 値 |
|---|---|
| フォーマット | mars-face-kit 独自バイナリ |
| ロード元 | `libmars-face-kit.a` + `MNN.framework`（デバイススライスのみ） |
| 置き換え可否 | **不可** — 独自フォーマット |
| 出力 | [0,1] 正規化キーポイント。reshape に最低 106 点、メイクメッシュに 111 点 |

モデルは**デバイスでのみロード**されます（シミュレータスライスは MNN/mars を
リンクしません）。シミュレータにはモデルが無いため、ランドマークもありません。

---

## アセットフローの要約

| 項目 | iOS |
|---|---|
| アセット元 | `prebuilt-sdk/ios/res` + `models` |
| アプリへの取り込み | CocoaPods がアプリバンドルへコピー（ビルド時） |
| リソースルート | `SetResourceRoot([NSBundle resourcePath])` |
| ホットリロード | 不可（バンドルは読み取り専用。再ビルドが必要） |
| PNG 圧縮 | なし（copy-resources、そのまま） |

# フィルタの拡張

iOS パイプラインは **2 つの ObjC++ ブリッジファイル**で構築されます。

- `native-bridge/GPUPixelImageView.mm` → `_ensurePipeline`
- `native-bridge/GPUPixelCameraView.mm` → `_setupGPUPixelPipeline`

C++ エンジンは**ビルド済み**（`BeautyFilter.xcframework`）のため、iOS 側から
**フィルタのソースは編集できません**。可能なのは次のみです。

1. **エンジンに既存のフィルタ**（例: `LipstickFilter`）をグラフへ組み込む。
2. 完全に新しいフィルタが必要な場合は、GPUPixel ソースから xcframework を再
   ビルドする（この iOS リポジトリの範囲外）。

エンジン既存フィルタをグラフへ追加するパターン:

1. **両方の**ビューに `std::shared_ptr<XxxFilter>` の ivar を追加します。
2. `_ensurePipeline` / `_setupGPUPixelPipeline` で `Create()` します。
3. 終端 `_beauty` の前でグラフへ組み込みます。
4. フィルタが顔メッシュを必要とするならランドマークを渡します。
5. UI 制御が必要ならビューマネージャ経由でセッターと prop を追加します。
6. teardown / stopCamera で `.reset()` します。
7. 静止画とカメラの双方へ適用するため、**両方のビューで繰り返します**。

## 現行パイプライン

```text
_source -> _blusher -> _reshape -> _beauty -> _sinkView
```

```cpp
_source->AddSink(_blusher);
_blusher->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

`_sinkView` は常に終端のため、`_beauty->AddSink(_sinkView)` は固定です。新しい
フィルタは `_beauty` の**前**に挿入します。

## 例: LipstickFilter の有効化

`LipstickFilter` はビルド済みエンジンに既に存在します。

```text
prebuilt-sdk/ios/include/gpupixel/filter/lipstick_filter.h   (ヘッダ)
prebuilt-sdk/ios/res/mouth.png                               (テクスチャ、バンドル済み)
```

`LipstickFilter` は `FaceMakeupFilter` を継承するため、チークと同様に完全な
ランドマークメッシュを必要とします。

### 1. ivar を追加（両ビュー）

`GPUPixelCameraView.mm` と `GPUPixelImageView.mm` で:

```cpp
std::shared_ptr<LipstickFilter> _lipstick;
```

`gpupixel/gpupixel.h` が公開していない場合はヘッダをインクルードします。

```cpp
#include "gpupixel/filter/lipstick_filter.h"
```

### 2. 生成してグラフへ組み込む

次を:

```cpp
_source->AddSink(_blusher);
_blusher->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

このように置き換えます:

```cpp
_lipstick = LipstickFilter::Create();

_source->AddSink(_blusher);
_blusher->AddSink(_lipstick);
_lipstick->AddSink(_reshape);
_reshape->AddSink(_beauty);
_beauty->AddSink(_sinkView);
```

### 3. ランドマークを渡す

カメラ（`captureOutput:`）:

```cpp
#ifdef GPUPIXEL_ENABLE_FACE_DETECTOR
  if (_blusher)  _blusher->SetFaceLandmarks(landmarks);
  if (_lipstick) _lipstick->SetFaceLandmarks(landmarks);
  if (_reshape)  _reshape->SetFaceLandmarks(landmarks);
#endif
```

静止画（`_reprocess`）: `_faceDetector->Detect(...)` の後に同様。

### 4. パラメータを適用

両ビューの `_applyParams` で:

```cpp
if (_lipstick) _lipstick->SetBlendLevel(_lipstickLevel / 10.0f);
```

ivar `float _lipstickLevel;` とセッターを追加します。

```objc
- (void)setLipstick:(float)value {
  _lipstickLevel = value;
  if (_lipstick) _lipstick->SetBlendLevel(value / 10.0f);  // カメラ: 即時適用
  // 画像ビュー: 上の行を [self _scheduleReprocess]; に置き換える
}
```

### 5. prop を React Native へ公開

`GPUPixelViewManager.mm` と `GPUPixelImageViewManager.mm`:

```objc
RCT_EXPORT_VIEW_PROPERTY(lipstick, float)
```

`src/index.ts`:

```ts
export interface FilterParamProps {
  smoothing?: number;
  whitening?: number;
  faceSlim?: number;
  eyeEnlarge?: number;
  blusher?: number;
  lipstick?: number;  // 新規
}
```

`App.tsx`:

```ts
const SLIDERS = [
  // ...
  { key: 'lipstick', label: 'Lipstick' },
];
```

### 6. Teardown

カメラの `stopCamera` と画像の `_teardown` で:

```cpp
_lipstick.reset();
```

## React Native 統合の注意

- **カメラビュー:** セッターはエンジンへ即時適用されます（次のフレームに反映）。
- **画像ビュー:** セッターは静止画を再描画するため `_scheduleReprocess` を
  呼ぶ必要があります（フレームループが無いため）。ランループ末尾でまとめること
  で冗長な描画を回避します。

## 完全に新しいフィルタの作成

これは **GPUPixel ソース**を編集して xcframework を再ビルドする必要があり、iOS
リポジトリ単独では行えません。手順（エンジンリポジトリ側）:

- `Filter` を継承し、`InitWithShaderString(vertex, fragment)`。
- プログラム初期化後に uniform location を取得。
- `OnRenderWithTexture` をオーバーライドし、uniform を設定して描画。
- マルチパス／マルチ入力には `BeautyFaceFilter` のように `FilterGroup` を使用。
- デバイス・シミュレータ両スライスを再ビルドし、ヘッダを
  `include/gpupixel/filter/` へエクスポート。
- `BeautyFilter.xcframework` を再パッケージし、`prebuilt-sdk/ios/` へ配置。

## シェーダの互換性

GPUPixel エンジンはプラットフォーム間でシェーダを共有します（OpenGL ES /
デスクトップ / WebGL）。クロスプラットフォームビルドを壊さないよう、新しい
シェーダは近隣フィルタと同じパターンに保ってください。

## チェックリスト

- [ ] フィルタはビルド済みエンジンに既に存在するか？（無ければ xcframework を再ビルド）
- [ ] shared_ptr ivar を**両ビュー**に追加。
- [ ] `_ensurePipeline` / `_setupGPUPixelPipeline` で `Create()`。
- [ ] グラフへ `_beauty` の**前**に組み込む。
- [ ] メッシュが必要なら `captureOutput:`（カメラ）と `_reprocess`（画像）で
      ランドマークを渡す。
- [ ] `_applyParams`（両ビュー）でパラメータを適用。
- [ ] セッター: カメラは即時適用、画像は `_scheduleReprocess`。
- [ ] 両マネージャで `RCT_EXPORT_VIEW_PROPERTY`。
- [ ] `FilterParamProps`（index.ts）とスライダーリスト（App.tsx）を更新。
- [ ] teardown / stopCamera で `.reset()`。
- [ ] 実機 iPhone でテスト（ランドマーク駆動フィルタはシミュレータで動作しない）。
- [ ] 顔の無いフレームをテスト。フィルタはパススルーする必要がある。

# iOS 統合ガイド

このガイドは、現行の React Native デモを実行するだけでなく、SDK をより深く統合
したい iOS チーム向けです。

## 現状

SDK はローカル CocoaPod `BeautyFilterSDK` を通じてアプリに公開されます。

現在の統合形態:

- React Native アプリは `requireNativeComponent` を使用します。
- ネイティブブリッジは ObjC++ で書かれています。
- GPUPixel エンジンは `BeautyFilter.xcframework` にパッケージ化されています。
- デバイスの顔検出は `MNN.framework` と `libmars-face-kit.a` を使用します。
- ランタイムリソースは CocoaPods によってアプリバンドルへコピーされます。

これはデモや概念実証には適しています。本番統合のためには、iOS チームは API
サーフェス、ライフサイクル処理、エラーハンドリング、パッケージングを標準化する
べきです。

## 統合パス 1: React Native を維持しブリッジを改善

メインアプリが React Native のままのチームに適しています。

推奨作業:

- JS ラッパーを専用パッケージ（例: `@company/beauty-filter-react-native`）へ抽出。
- `GPUPixelImageView` と `GPUPixelCameraView` に安定した公開 API を定義。
- ネイティブ → JS のイベントコールバックを追加:
  - `onReady`
  - `onError`
  - `onCameraPermissionDenied`
  - `onFaceDetected`
  - `onFrameStats`
- パラメータ範囲とプリセットを標準化:
  - `natural`
  - `beauty`
  - `makeup`
  - `custom`
- prop 駆動 API を継続するなら、旧来の命令型ネイティブモジュールを削除または
  非推奨化。

prop 駆動 API を推奨する理由: React Native New Architecture では、
`RCTUIManager viewRegistry` 経由の命令型参照は、ネイティブビューへ直接 prop を
渡すより信頼性が低くなることがあるためです。

## 統合パス 2: ネイティブ iOS SDK レイヤーを作成

React Native への依存なしに Swift/ObjC から直接 SDK を使いたいチームに適して
います。

推奨分割:

```text
BeautyFilterCore
├── GPUPixel エンジンラッパー
├── リソース／モデルのロード
├── 顔検出のライフサイクル
├── 画像処理 API
└── カメラ処理 API

BeautyFilterReactNative
├── RCTViewManager
├── requireNativeComponent ラッパー
└── React Native のイベント／prop マッピング
```

推奨ネイティブ API:

```objc
@interface BeautyFilterConfig : NSObject
@property(nonatomic) float smoothing;
@property(nonatomic) float whitening;
@property(nonatomic) float faceSlim;
@property(nonatomic) float eyeEnlarge;
@property(nonatomic) float blusher;
@end

@interface BeautyFilterImageProcessor : NSObject
- (UIImage *)processImage:(UIImage *)image config:(BeautyFilterConfig *)config error:(NSError **)error;
@end

@interface BeautyFilterCameraView : UIView
- (void)startWithConfig:(BeautyFilterConfig *)config;
- (void)stop;
- (void)updateConfig:(BeautyFilterConfig *)config;
@end
```

Swift アプリ向けには、Swift フレンドリなラッパーを公開します。

```swift
let config = BeautyFilterConfig(
    smoothing: 0.6,
    whitening: 0.2,
    faceSlim: 0.03,
    eyeEnlarge: 0.03,
    blusher: 0.2
)

cameraView.start(config: config)
```

## 本番パッケージング

推奨オプション:

- 組織が既に CocoaPods を使用しているなら、プライベートな CocoaPods spec repo を
  使用。
- またはバイナリ／リソース構成が SPM 向けに標準化されているなら Swift Package
  としてパッケージ化。
- セマンティックバージョニング `MAJOR.MINOR.PATCH` で SDK をバージョン管理。
- 本番 podspec のプレースホルダ `homepage`、`source`、`author` メタデータを
  置き換え。
- バイナリ SDK 更新ごとに変更履歴を維持。

本番利用の前に明確化すべき点:

- SDK を顧客アプリ内へ再配布できるか。
- GPUPixel、MNN、mars-face-kit のライセンス。
- バイナリサイズへの影響。
- 最小 iOS バージョン。
- サポート対象のデバイスアーキテクチャ。
- 期待されるシミュレータサポートレベル。

## リソースとモデルの管理

現行の pod は `res/` と `models/` をメインバンドルへコピーします。本番向けには
次を検討します。

- ホストアプリとの衝突を避けるためリソースを名前空間化。
- 初期化時に必須リソースを検証し、明確なエラーを報告。
- モデルファイルへチェックサムやバージョンを追加。
- SDK を別途パッケージ化する場合、framework バンドルからのリソース読み込みを
  サポート。

より安全な本番レイアウト:

```text
BeautyFilterResources.bundle
├── res/
└── models/
```

ネイティブコアは、メインバンドルを前提とせず、明示的な `NSBundle` または
リソースルートを受け取るべきです。

## カメラライフサイクルのハードニング

デモは現在 `didMoveToWindow` に基づいてカメラキャプチャを開始・停止します。本番
統合では次を追加すべきです。

- アプリのバックグラウンド／フォアグラウンド遷移に対する pause/resume API。
- `AVCaptureSession` の中断処理。
- 権限拒否のコールバックによる報告。
- 製品が必要とする場合のフロント／バックカメラ選択。
- ビュー消失時の明示的な GPU／ネイティブリソースのクリーンアップ。
- 状態報告: `starting`、`running`、`stopped`、`failed`。

## パフォーマンスと安定性

iOS チームがレビューすべき領域:

- カメラフレーム処理中にフィルタパラメータが更新される際のスレッドセーフティ。
- スライダーが多数の変更を発火するカメラモードでのパラメータ更新のまとめ。
- `captureOutput` 内のアロケーション削減。
- 旧世代ターゲットデバイスでの FPS ベンチマーク。
- 大きな画像のデコード時のメモリピーク。
- 入力画像サイズの上限、または前処理でのダウンスケール。
- リリースビルドで無効化できる構造化ロギング。
- `NSLog` のみではなくエラーオブジェクト。

測定すべきメトリクス:

- パイプライン初期化時間。
- フレームあたりの顔検出時間。
- 美容有効時のカメラ FPS。
- 12MP/24MP 画像のメモリピーク。
- SDK によるアプリサイズ増加。

## パラメータ API の標準化

デモは公開 UI 範囲 `0-10` を使用し、エンジン向けに値をスケールダウンします。

本番 API が定義すべき点:

- 公開範囲: `0-1`、`0-10`、または `0-100`。
- 各ユースケースの既定値。
- ネイティブ側の min/max クランプ。
- プリセットのバージョニング。SDK 更新時に旧プリセットの挙動が静かに変わらない
  ようにする。

現行マッピング:

| 公開 prop | デモ範囲 | エンジン値 |
| --- | ---: | --- |
| `smoothing` | 0-10 | `value / 10` |
| `whitening` | 0-10 | `value / 20` |
| `faceSlim` | 0-10 | `value / 200` |
| `eyeEnlarge` | 0-10 | `value / 100` |
| `blusher` | 0-10 | `value / 10` |

## iOS チーム向けテスト計画

推奨される最小テストグループ:

- シミュレータビルドが成功する。
- デバイスビルドが成功する。
- カメラ権限なしでアプリ起動がクラッシュしない。
- 画像プロセッサが異なる EXIF 向きの画像を処理できる。
- 非常に大きな画像が許容メモリ閾値を超えない。
- カメラを繰り返し開始・停止してもセッションやビューがリークしない。
- カメラ実行中のアプリのバックグラウンド／フォアグラウンド。
- 権限の denied/restricted 状態。
- デバイスにフロントカメラが無い、またはカメラが使用中。
- ベースライン画像セットによるビジュアルリグレッションチェック。

## 本番引き継ぎチェックリスト

- 本番 podspec のメタデータ、ライセンス、バージョンが正しい。
- バイナリ SDK がリリース要件に従ってストリップ／シンボル管理されている。
- リソースとモデルが専用の名前空間でパッケージ化されている。
- iOS と React Native の双方に公開 API ドキュメントが存在する。
- `NSLog` を超えるエラーハンドリングが存在する。
- iOS チームが React Native を使わない場合、ネイティブ iOS サンプルアプリが存在
  する。
- ターゲットデバイスのベンチマークが利用可能。
- バイナリ SDK の更新方針と変更履歴が定義されている。
- トラブルシューティングガイドが framework／静的ライブラリのリンク問題を網羅する。

## 推奨アップグレードロードマップ

1. オンボーディング: 現行デモを維持し、ビルド／ドキュメントのチェックリストを
   追加。
2. 安定化: 公開 API を定義し、パラメータをクランプし、イベントコールバックと
   エラーハンドリングを追加。
3. ネイティブ SDK レイヤー: React Native から独立した `BeautyFilterCore` を作成。
4. パッケージング: 本番 CocoaPod/SPM パッケージとリソースバンドルを作成。
5. 本番ハードニング: ベンチマーク、メモリ／FPS テスト、ライフサイクルテスト、
   ビジュアルリグレッションチェックを追加。

# DAYO Beauty Filter (iOS) — プレゼンテーション

> スライド形式。各 `## ` セクションが 1 枚のスライドです。*トークポイント* 行は
> 発表者ノートです。

---

## スライド 1 — はじめに

**DAYO Beauty Filter — iOS** — **React Native + ネイティブブリッジ**で構築した、
iPhone 向けの**リアルタイム**美容カメラアプリ。

- カメラ／画像 → 肌のスムージング、美白、小顔、目の拡大、チーク → 即時表示。
- **GPUPixel** エンジンを通じて GPU 上で動作。
- 3 レイヤーアーキテクチャ: **React Native (UI)** + **ObjC++ (ブリッジ)** +
  **C++/OpenGL (ビルド済みエンジン)**。

*トークポイント:* 「iOS 向けのリアルタイム美容アプリです。重い処理は C++ エンジン
が GPU 上で行い、薄い ObjC++ ブリッジが React Native と接続します。」

---

## スライド 2 — 課題

リアルタイム美容化は 3 点で難しいです。

1. **速度** — 毎秒約 30 フレーム、各 720p フレームには数十万ピクセルがあります。
2. **遅延** — 画像はほぼ即座に、カクつかずに表示される必要があります。
3. **顔ベースの効果** — 小顔／目の拡大／チークは、目・口・顔輪郭の位置を知る必要が
   あります。

→ 解決策: 画像処理を **GPU**（GPUPixel）へ移し、**AI ランドマーク検出**
（mars-face-kit）を使用します。

---

## スライド 3 — 技術スタック

| レイヤー | 技術 |
|---|---|
| アプリ / UI | React Native + TypeScript、スライダー、画像ピッカー |
| ブリッジ | ObjC++ `RCTViewManager`（prop 駆動） |
| カメラ | `AVCaptureSession`、フロントカメラ、BGRA `1280x720` |
| 画像処理 | C++17、**GPUPixel**、**OpenGL ES**、`BeautyFilter.xcframework` として梱包 |
| 顔検出 | **MNN.framework** + **libmars-face-kit.a**（デバイスのみ） |
| リソース | CocoaPods が `res/` + `models/` をアプリバンドルへコピー |
| ビルド | CocoaPods + `bootstrap.sh`（RN アプリを生成） |
| デバイス | iOS 15.1+、arm64 |

---

## スライド 4 — 全体アーキテクチャ

```
┌──────────────── React Native (TypeScript) ────────────────┐
│  App.tsx  →  props (smoothing, whitening, faceSlim, ...)   │
└───────────────────────────────┬───────────────────────────┘
                                 │  RCTViewManager (prop 駆動)
┌────────────────────────────────▼──────────────────────────┐
│              ObjC++ Bridge (native-bridge/*.mm)            │
│   GPUPixelCameraView / GPUPixelImageView                  │
└────────────────────────────────┬──────────────────────────┘
                                 │  shared_ptr<...> (C++)
┌────────────────────────────────▼──────────────────────────┐
│          C++ / OpenGL ES — GPUPixel (xcframework)         │
│   SourceRawData → Blusher → Reshape → BeautyFace → SinkView│
└───────────────────────────────────────────────────────────┘
```

- **React Native**: UI、スライダー、画像ピッカー、prop によるパラメータ受け渡し。
- **ObjC++**: カメラ（AVFoundation）、画像デコード、ビューライフサイクル。
- **C++/GPU**: 色変換、美容フィルタ、顔のワーピング、レンダリング。

*トークポイント:* 「フレームの取得元はプラットフォームで異なります（iOS では
AVFoundation + ObjC++）が、GPU パイプラインは同一の共有 C++ エンジンです。」

---

## スライド 5 — 1 フレームの流れ（カメラ）

1. `AVCaptureSession` がフロントカメラから **BGRA** フレームを返します
   （AVFoundation が回転・ミラー済み）。
2. フレームに対し顔検出を実行 → ランドマークを更新。
3. BGRA のピクセルポインタをそのまま `SourceRawData::ProcessData` へ渡す。
4. GPU: シェーダで BGRA→RGB に変換し、フィルタチェーンを実行。
5. **`SinkView`**（`UIView` 上の `CAEAGLLayer`）へ直接描画 — **リードバックなし**。

→ カメラと静止画の両モードが直接描画し、**画像が CPU へコピーバックされません**。

---

## スライド 6 — フィルタパイプライン

```
SourceRawData (BGRA/RGBA → RGB)
   → BlusherFilter        (チーク、ランドマーク駆動)
   → FaceReshapeFilter    (小顔 + 目の拡大、ランドマークワープ)
   → BeautyFaceFilter     (肌のスムージング + 美白)
   → SinkView (画面へ描画)
```

| 効果 | フィルタ | ランドマーク必要？ |
|---|---|---|
| 肌のスムージング | `BeautyFaceFilter` | いいえ |
| 美白 | LUT (`BeautyFaceUnitFilter`) | いいえ |
| 小顔 | `FaceReshapeFilter` | はい |
| 目の拡大 | `FaceReshapeFilter` | はい |
| チーク | `BlusherFilter` | はい |

*トークポイント:* 「5 つのうち 3 つの効果はランドマークが必要 — デバイス限定です。
シミュレータでもスムージング + 美白は可能です。」

---

## スライド 7 — 顔ランドマーク

- **mars-face-kit + MNN** を使用 → `[0,1]` 正規化ランドマークを返します。
- 駆動対象: 顔輪郭（小顔）、目（目の拡大）、頬（チーク）。
- **デバイス限定:** MNN + mars は `iphoneos` スライスのみ。シミュレータスライスは
  顔検出をオフにしてビルドされます。
- カメラは `VIDEO` モード（BGRA）、静止画は `PICTURE` モード（RGBA）を使用。

*トークポイント:* 「検出は現在、毎フレーム・フルレゾリューションで動作 — FPS の
ために最初に最適化したい箇所です。」

---

## スライド 8 — パフォーマンス

1. **リードバックなし** — カメラも画像も `SinkView` へ直接描画。
2. **BGRA を GPU へ直送** — カメラが BGRA を生成し、色変換はシェーダで。
3. **回転／ミラーは AVFoundation** — `videoOrientation` + `videoMirrored`、CPU
   回転なし。
4. **`alwaysDiscardsLateVideoFrames`** — パイプライン混雑時に遅延フレームを破棄。
5. **再処理のまとめ（静止画）** — 複数スライダー変更をランループ末尾の 1 回の描画
   にまとめる。
6. **ビューごとのパイプライン** — 各ビューが独自グラフを持ち、グローバル状態の
   競合なし。

> **注意:** 顔検出が**毎フレーム・フルレゾリューション**で動作しています。FPS 向上
> の最初の候補です。

---

## スライド 9 — アプリの機能

- 🖼️ **画像**モード: ライブラリから写真を選択 → 美容パイプラインを実行
  （シミュレータでも動作）。
- 📷 **カメラ**モード: リアルタイムのフロントカメラ（実機 iPhone のみ）。
- 🎚️ **5 つのスライダー** `0..10`: スムージング、美白、小顔、目の拡大、チーク。
- ⚙️ 完全に **prop 駆動**の制御 — prop を変更すると再描画。

---

## スライド 10 — 2 つの処理経路

| | **カメラ**（ライブ） | **静止画** |
|---|---|---|
| ビュー | `GPUPixelCameraView` | `GPUPixelImageView` |
| 入力 | AVCaptureSession からの BGRA | UIImage デコードからの RGBA |
| 検出モード | `VIDEO` | `PICTURE` |
| トリガー | ビュー表示時に自動開始 | prop 変更時に再処理 |
| 出力 | `SinkView`（ライブ） | `SinkView`（ランループごとに 1 回） |
| リードバック | **なし** | **なし** |

*トークポイント:* 「同じ GPU パイプライン、同じ `SinkView` の出力先。違いは
フレームの取得元と描画トリガーだけです。」

---

## スライド 11 — 共有エンジン

- **GPUPixel C++ エンジンを共有**（他プラットフォームと）。
- ObjC++ ブリッジ（`GPUPixelCameraView.mm`）は同じエンジン API を呼びます。
- パラメータのスケーリングは一貫しています（`smoothing/10`、`whitening/20`、
  `faceSlim/200`、`eyeEnlarge/100`、`blusher/10`）。
- → 1 つの C++ コア、複数のプラットフォーム。

---

## スライド 12 — 推奨デモ

1. アプリを開く → **画像**タブ → ポートレートを選択 → 既定の美容効果を確認。
2. **スムージング／美白**をドラッグ → 肌が即座に変化（シミュレータでも動作）。
3. 実機 iPhone で → **カメラ**タブ → 権限を許可 → リアルタイム美容を確認。
4. **小顔／目の拡大／チーク**をドラッグ → ワーピングとチークが顔に追従。

---

## スライド 13 — まとめ

- iOS 美容アプリ: **React Native のオーケストレーション + ObjC++ ブリッジ +
  C++/OpenGL エンジン**。
- 共有 GPUPixel エンジンを再利用 → 工数削減、効果の一貫性。
- `SinkView` へ直接描画、両モードともリードバックなし。
- 5 つの効果。うち 3 つは AI ランドマークで顔に追従（デバイス限定）。

**詳細ドキュメント:** `reference/architecture.md`、`reference/filter-pipeline.md`、
`reference/api-bridge.md`、`reference/face-landmarks.md`、
`getting-started/build-guide.md`、`reference/assets-and-textures.md`、
`reference/extending-filters.md`。
