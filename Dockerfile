FROM maven:3.8.6-openjdk-8

WORKDIR /app

# Copy Git configuration for submodules
COPY .git .git/
COPY .gitmodules .gitmodules

# Initialize and update submodules
RUN git submodule init && git submodule update --recursive

# Copy pom.xml first for dependency caching
COPY pom.xml .
COPY jar-with-all-dependencies.xml .

# Copy lib directory (includes satd_detector.jar and submodule will be handled by git)
COPY lib/ lib/

# Copy source code
COPY src/ src/

# Build the project
RUN mvn clean package -DskipTests

# Verify generated JAR file
RUN ls -lh target/

