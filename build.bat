@echo off
cd /d c:\workspace\tampa\nexus-mcp-sidecar
set JAVA_HOME=C:\tool\java\jdk-21.0.5+11
call C:\tool\java\apache-maven-3.9.15\bin\mvn.cmd -DskipTests clean package > build.log 2>&1
