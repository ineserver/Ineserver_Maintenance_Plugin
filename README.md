# Ineserver Maintenance Plugin

Velocity用の自動メンテナンス管理プラグインです。Googleカレンダーと連携し、メンテナンス時間の通知とサーバーアクセス制御を自動化します。

注意：サーバー起動後 check-interval-minutes に記載された時間が立たないと最初の確認を行いません。

必須プラグイン：LuckPerms（グループ名：Adminに接続が許可されます）

動作環境：Velocity

## コマンド

- `/maintenance end` - メンテナンスを終了（権限：maintenance.admin）
- `/maintenance status` - メンテナンス状態を確認（権限：maintenance.admin）
- `/maintenance schedule` - 次回メンテナンス予定を確認

## 権限
maintenance.notice.off ： すべての通知を表示しない