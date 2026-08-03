@echo off
cd /d c:\workspace\tampa\nexus-mcp-sidecar
set JAVA_HOME=C:\tool\java\jdk-21.0.5+11
C:\tool\java\jdk-21.0.5+11\bin\java.exe -jar target\nexus-mcp-sidecar-0.1.0-SNAPSHOT.jar > run.log 2>&1
