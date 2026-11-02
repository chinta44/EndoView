# EndoView 内視鏡ビューア

USB内視鏡 (UVC) 用の Android アプリ。見やすさ重視の自作用。

- プレビュー / 写真 / 動画 / 解像度切替 / 回転・反転
- UVC 画質調整スライダー + プリセット (暗所・配管 / 金属 / 基板・文字)
- フリーズ拡大、ピンチズーム、グリッド、音量ボタンシャッター
- 権限はカメラのみ。位置情報・広告・ネット通信なし

仕様は [SPEC.md](SPEC.md) を参照。

## ビルド

GitHub に push すると GitHub Actions がデバッグ APK をビルドする。
完成した APK は Artifacts (`EndoView-debug-apk`) と
Releases の `v0.1` からダウンロードできる。

なお、このリポジトリには `gradle/wrapper/gradle-wrapper.jar` を含めていないため、
CI では Gradle 8.7 本体を直接使ってビルドしている。
ローカルで `./gradlew` を使いたい場合は、Gradle 8.7 を入れた環境で一度だけ
`gradle wrapper --gradle-version 8.7` を実行して jar を生成すること。

ローカルビルド (Android Studio で開くか):

```bash
gradle assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## 使い方

1. アプリを入れたスマホに USB 内視鏡を接続する
2. USB デバイスの許可ダイアログで OK (初回のみカメラ権限も)
3. LED の明るさは内視鏡ケーブルのダイヤルで調整
4. 「調整」ボタンで画質スライダーとプリセット、「フリーズ」で静止拡大確認

## ライセンス / 謝辞

UVC カメラ処理に [UVCAndroid](https://github.com/shiyinghan/UVCAndroid)
(Apache License 2.0、saki4510t/UVCCamera 系) を使用。
