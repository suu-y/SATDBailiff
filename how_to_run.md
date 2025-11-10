# SATDBailiff 実行手順

## 前提条件

- Docker Desktop
- MySQL Server
- Git

## 事前準備

### 1. MySQLデータベースの初期化

```bash
mysql -u root -e "CREATE DATABASE satd;"
mysql -u root satd < sql/satd.sql
```

### 2. 設定ファイルの準備

- `mySQL.properties`: MySQL接続情報を設定
- `repos.txt`: 分析対象リポジトリを記載

### 3. JavaParserを除いたJARの作成（初回のみ）

```bash
cd lib/DebtHunter-Tool
mkdir -p tmp && cd tmp
unzip -q ../DebtHunter-Tool.jar
rm -rf com/github/javaparser META-INF/maven/com.github.javaparser
jar cf ../DebtHunter-Tool-no-javaparser.jar *
cd .. && rm -rf tmp
```

## 実行手順

### ステップ1: ビルド

```bash
docker build -f Dockerfile -t satd-builder-debthunter .
docker create --name temp-container satd-builder-debthunter
docker cp temp-container:/app/target/satd-analyzer-jar-with-all-dependencies.jar target/
docker rm temp-container
```

### ステップ2: 実行用イメージのビルド

```bash
docker build -f Dockerfile.run -t satd-analyzer-debthunter .
```

### ステップ3: 分析の実行

```bash
docker run --add-host=host.docker.internal:host-gateway satd-analyzer-debthunter
```

> リビジョン全体のSATDスナップショットも保存したい場合は、上記コマンドに `--capture-snapshots` オプションを追加してください。

### ステップ4: 結果の確認

```bash
mysql -u root
USE satd;
SELECT COUNT(*) FROM Projects;
SELECT COUNT(*) FROM Commits;
SELECT COUNT(*) FROM SATD;
SELECT COUNT(DISTINCT satd_instance_id) FROM SATD WHERE satd_instance_id IS NOT NULL;
```

## トラブルシューティング

### データベースをクリアして再実行

```bash
mysql -u root -e "DROP DATABASE satd; CREATE DATABASE satd;"
mysql -u root satd < sql/satd.sql
```

### repos.txtを変更した場合

実行用イメージを再ビルド：

```bash
docker build -f Dockerfile.run -t satd-analyzer-debthunter .
```

### サブモジュールを更新

```bash
git submodule update --remote lib/DebtHunter-Tool
```
