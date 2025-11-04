# SATDBailiff 実行手順

このドキュメントでは、オリジナルのプロジェクトを実行可能にするために行った変更と、実行方法をまとめます。

## 発生した問題と解決策

### 1. Java 17互換性問題
**問題**: ローカル環境がJava 17のため、以下のエラーが発生：
- `InaccessibleObjectException`: Wekaライブラリのモジュールアクセスエラー
- `NoClassDefFoundError`: 依存関係の欠落

**解決**: Docker（Java 8環境）を使用してビルド・実行

### 2. MySQL接続エラー
**問題**: `Unable to load authentication plugin 'caching_sha2_password'`
- MySQL Connector/J 5.1.45がMySQL 8.0の認証方式に非対応

**解決**: MySQL Connector/Jを8.0.33にアップグレード

### 3. JavaFX依存関係エラー
**問題**: `javafx.util.Pair`クラスが見つからない

**解決**: `java.util.AbstractMap.SimpleEntry`に置き換え

---

## 実施した変更

### 1. コード修正

#### `src/main/java/edu/rit/se/satd/mining/diff/OldFileDifferencer.java`
```java
// 変更前
import javafx.util.Pair;

// 変更後
import java.util.AbstractMap;

// 使用箇所も変更
new AbstractMap.SimpleEntry<>(diffEntry, ...)
```

### 2. `pom.xml`の修正

```xml
<!-- MySQL Connector/Jのバージョンアップ -->
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <version>8.0.33</version>  <!-- 5.1.45 から変更 -->
</dependency>
```

### 3. Dockerファイルの作成

#### `Dockerfile` (ビルド用)
```dockerfile
FROM maven:3.8.8-openjdk-8 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src
COPY lib ./lib

RUN mvn clean package -DskipTests

# 生成物の確認
RUN ls -lh target/
```

#### `Dockerfile.run` (実行用)
```dockerfile
FROM maven:3.8.6-openjdk-8

WORKDIR /app

# JARファイルと設定ファイルをコピー
COPY target/satd-analyzer-jar-with-all-dependencies.jar .
COPY repos.txt .
COPY mySQL.properties .

# MySQLホストをコンテナから接続できるように設定
RUN sed -i 's/URL=localhost/URL=host.docker.internal/' mySQL.properties

# 分析実行
CMD ["java", "-jar", "satd-analyzer-jar-with-all-dependencies.jar", "-r", "repos.txt", "-d", "mySQL.properties", "-e"]
```

### 4. MySQL設定

#### データベースの初期化
```bash
# データベース作成
mysql -u root -e "CREATE DATABASE satd;"

# スキーマのインポート
mysql -u root satd < sql/satd.sql
```

#### `mySQL.properties`
```properties
USERNAME=root
PASSWORD=
PORT=3306
URL=localhost
DB=satd
USE_SSL=false
```

---

## 実行方法

### 前提条件
- Docker Desktop がインストール済み
- MySQL Server がインストール済み（ローカルまたはリモート）

### ステップ1: ビルド

```bash
# ビルド用イメージの作成とビルド実行
docker build -f Dockerfile -t satd-builder .

# JARファイルをローカルにコピー
docker run --rm -v $(pwd)/target:/output satd-builder sh -c "cp /app/target/satd-analyzer-jar-with-all-dependencies.jar /output/"
```

### ステップ2: 実行環境の準備

```bash
# 実行用イメージのビルド
docker build -f Dockerfile.run -t satd-analyzer-run .
```

### ステップ3: 分析対象リポジトリの設定

`repos.txt`を編集：
```
# GitHub リポジトリ（特定のタグまで）
https://github.com/apache/commons-io.git,rel/commons-io-2.20.0

# 全履歴を分析
https://github.com/apache/commons-io.git

# 複数リポジトリ
https://github.com/apache/commons-io.git
https://github.com/apache/commons-lang.git
```

### ステップ4: SATD分析の実行

```bash
# Docker内で実行（MySQLはホストマシン上）
docker run --add-host=host.docker.internal:host-gateway satd-analyzer-run
```

### ステップ5: 結果の確認

```bash
# MySQL に接続
mysql -u root

# データベースを選択
USE satd;

# 基本統計
SELECT COUNT(*) FROM Projects;
SELECT COUNT(*) FROM Commits;
SELECT COUNT(*) FROM SATD;

# SATDの解決状況
SELECT resolution, COUNT(*) FROM SATD GROUP BY resolution;
```


## トラブルシューティング


### 既存データをクリアして再実行
```bash
# データベースを再作成
mysql -u root -e "DROP DATABASE satd; CREATE DATABASE satd;"
mysql -u root satd < sql/satd.sql

# 再度分析実行
docker run --add-host=host.docker.internal:host-gateway satd-analyzer-run
```
