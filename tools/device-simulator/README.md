# device-simulator

IoT のデバイスのシミュレータ(P11。ADR-0039)。Kotlin/Native の実行ファイル(linuxX64・macosArm64)で、SDK の MQTT の部分(`shared/integration-sdk/mqtt`。ADR-0038)だけを使ってテレメトリを MQTT Broker に送る。

## 組み立て
```bash
./gradlew :tools:device-simulator:linkReleaseExecutableMacosArm64   # macOS(Apple Silicon)
./gradlew :tools:device-simulator:linkReleaseExecutableLinuxX64     # Linux(x86_64)
# tools/device-simulator/build/bin/<target>/releaseExecutable/device-simulator.kexe
```
CI は build ジョブで linuxX64、macos ジョブで macosArm64 の実行ファイルを組み立て、成果物(`device-simulator-<target>`)にする。

## 使い方
パスワードは引数ではなく環境変数で渡す(既定 `MQTT_DEVICE_PASSWORD`。`--password-env` で名前を変えられる)。ユーザー名は `{tenant}/{deviceId}`、トピックは `devices/{tenant}/{deviceId}/telemetry`(ADR-0039 §3)。

```bash
MQTT_DEVICE_PASSWORD=... device-simulator.kexe --tenant t1 --device d-001 --count 5 --interval-ms 1000
```

| オプション | 内容 |
|---|---|
| `--payload-version 1\|2` | ペイロードの形式の版(`contracts/devices/telemetry.v{n}.schema.json`)。1 は古いファームウェア(温度と湿度の決まった 2 つ・UNIX 秒)、2 は名前・値・単位の一覧(既定) |
| `--offline <n>` | 通信が切れていた間に溜めていたものとして、接続の前の分を n 件、古い順にまとめて送る(デバイスの時刻は過去。ADR-0039 §8) |
| `--clock-skew-seconds <s>` | デバイスの時計のずれ(ADR-0039 §7) |
| `--no-clock` | デバイスの時刻を送らない(時計が合っていないデバイス) |
| `--traceparent <value>` | 親のトレース。各メッセージをその子にする(なければメッセージごとに新しいトレース) |

`--help` ですべてのオプションを出す。起動のたびに `boot`(乱数のセッション ID)を作り、`seq` を 0 から数える(ADR-0039 §5)。

## 出力と終了コード
送ったメッセージごとに、標準出力に 1 行の JSON を出す(ペイロードとパスワードは出さない)。E2E はこの `traceparent` でトレースをたどる。

```json
{"seq":0,"boot":"…","traceparent":"00-…-…-03","deviceTime":"2026-10-25T00:00:00Z","result":"ok","reasonCode":0}
```

終了コード: 0 = すべて送れた、1 = 送れなかったものがある(一時的な失敗は SDK が再接続して送り直した後)、2 = 引数・環境変数の誤り。
