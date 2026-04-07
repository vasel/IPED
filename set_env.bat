@echo off
set "JAVA_HOME=C:\java\jdk-11.0.16.1-full"
set "MAVEN_HOME=C:\java\apache-maven-3.8.6"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "HTTP_PROXY=proxy.fazenda.mg.gov.br:8003"
set "HTTPS_PROXY=proxy.fazenda.mg.gov.br:8003"

echo Environment configured for:
echo Java: %JAVA_HOME%
echo Maven: %MAVEN_HOME%
