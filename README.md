# Remote Device Control / P2P 通話アプリ

iPadのPWAから、所有者が初回登録したAndroid端末を管理し、許可されたカメラ・マイクのライブ配信へ接続するプロジェクトです。HTML / CSS / JavaScriptを中心に開発し、Android固有部分はKotlinを使わずJavaで実装する方針です。

## 現在の状態

- 既存の `index.html` / `style.css` / `script.js`：ブラウザ間のPeerJS通話デモ
- `docs/REMOTE_DEVICE_CONTROL_SPEC.md`：Android端末管理版の実装仕様
- `gas/README.md`：GASバックエンドの役割とセットアップ方針
- `android/README.md`：Android 13 companion appの設計メモ

**注意：** 現時点ではAndroid APK、GAS API、端末ペアリング、複数端末管理はまだ実装されていません。設計と実装を段階的に進めます。

## 採用技術

- iPad管理画面：GitHub Pages + HTML / CSS / JavaScript（PWA）
- Android APK：JavaによるAndroid連携 + JavaScript中心の画面
- APKビルド：GitHub Actions
- 管理・ペアリング・制御情報：Google Apps Script
- 映像・音声：WebRTC（GASをメディア中継に使用しない）

## Android 13の重要な制約

ユーザー操作でライブ配信サービスを開始した後、Foreground Serviceで画面消灯中の継続を検証します。一方、画面ロック中に停止状態からカメラ・マイクを新規起動することや、強制停止されたアプリを遠隔から復活させることは通常のAndroidアプリでは保証できません。Device Owner登録でもカメラ・マイクのプライバシー制限は解除されません。

カメラ・マイクが有効な間は、Android側に分かりやすい通知と停止操作を必ず表示します。所有者が明確に許可した端末のみを登録対象とします。

## 初期セットアップの目標

1. iPadで端末登録と一回限りのペアリングQRを発行。
2. AndroidアプリでQRを読み取り、所有者が登録を承認。
3. Androidでカメラ・マイク権限と通知を許可。
4. 以後はiPadの端末一覧から選択して接続。

詳細は [実装仕様](docs/REMOTE_DEVICE_CONTROL_SPEC.md) を参照してください。
