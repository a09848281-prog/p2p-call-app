# Android・iPad WebRTC 双方向通信 試作版 v0.1

このブランチは仕様 v3.0を**ブラウザだけで試すためのWebサイト版**です。Android APK・ネイティブアプリは含めません。

## 構成
- GitHub Pages: `web/` を公開（Android Chrome / iPad Safari）
- Google Apps Script: 接続コード・SDP・ICE候補のシグナリング
- WebRTC: カメラ・マイクのリアルタイム通信
- STUN: Google公開STUNを試験利用（TURNは未設定）

## 初回セットアップ
1. `gas/Code.gs` の内容をGoogle Apps Scriptプロジェクトの `Code.gs` に貼り付けて保存。
2. **デプロイ → 新しいデプロイ → ウェブアプリ**を選択。
3. 実行ユーザーを自分、アクセスできるユーザーを「全員」にしてデプロイし、URLを控える。
4. GitHubの **Settings → Pages → Build and deployment → Source** を **GitHub Actions** に設定する。
5. `web/` または `gas/` の更新時にGitHub Actionsがサイトを公開する。Actionsの `Deploy Remote Device Control PWA` が成功したら公開URLを開く。
6. AndroidとiPadで同じ公開URLをHTTPSで開き、GAS URLをそれぞれ保存する。
7. Androidで「待機を開始」→表示された6桁コードをiPadに入力→「接続する」。

## 試作版の機能
- Androidブラウザからカメラ・マイクを取得し、Offerを発行
- iPadブラウザからコードで参加し、Answerを返す
- GAS経由のSDP・ICE候補交換
- Android→iPadの映像・音声
- iPad→AndroidのPTT音声
- カメラ切り替え、Androidマイク・映像トラックの停止/再開
- 切断時のメディア解放

## 重要な制限
- 初期版はSTUNのみ。TURN未設定のため、異なるネットワーク間では接続できない場合があります。
- 画面ロック・バックグラウンドでのカメラ/マイク維持は保証しません。
- GASはシグナリング専用。映像・音声の中継はしません（TURN経由を除く）。
- 6桁コードは10分で期限切れ。個人テスト用であり、公開サービス向けの認証強度ではありません。
- GAS Webアプリは「全員」アクセス設定が必要なため、接続コードを他人に共有しないでください。
- 録音・録画・メディア保存は行いません。
- GitHub Pagesは公開ファイルです。秘密情報を書かないでください。

## 検査状況
- GitHub ActionsでJavaScript構文検査と必要ファイル確認を実行します。
- 実機のAndroid/iPadでのカメラ・音声・ネットワーク動作は、利用者の端末でのテストが必要です。未確認の機能を動作確認済みとは扱いません。
