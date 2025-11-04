FROM maven:3.8.6-openjdk-8

WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .
COPY jar-with-all-dependencies.xml .

# Copy lib directory
COPY lib/ lib/

# Copy source code
COPY src/ src/

# Build the project
RUN mvn clean package -DskipTests

# Verify generated JAR file
RUN ls -lh target/

