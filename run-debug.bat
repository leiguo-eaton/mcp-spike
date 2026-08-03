@echo off
cd /d c:\workspace\tampa\nexus-mcp-sidecar
set JAVA_HOME=C:\tool\java\jdk-21.0.5+11
C:\tool\java\jdk-21.0.5+11\bin\java.exe -jar target\nexus-mcp-sidecar-0.1.0-SNAPSHOT.jar --logging.level.org.springframework.security.web=DEBUG --logging.level.org.springframework.security.oauth2.server.resource=DEBUG --logging.level.org.springframework.security.oauth2.jwt=DEBUG --logging.level.org.springframework.security.oauth2.server.authorization=DEBUG > run-debug.log 2>&1
