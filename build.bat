@echo off
echo ========================================================
echo Iniciando o build do IPED (mvn clean install -DskipTests)
echo ========================================================

@echo off
set "JAVA_HOME=C:\java\jdk-11.0.16.1-full"
set "MAVEN_HOME=C:\java\apache-maven-3.8.6"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "HTTP_PROXY=proxy.fazenda.mg.gov.br:8003"
set "HTTPS_PROXY=proxy.fazenda.mg.gov.br:8003"

echo Environment configured for:
echo Java: %JAVA_HOME%
echo Maven: %MAVEN_HOME%

call mvn clean install -DskipTests -DskipRegripper=TRUE

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERRO] Ocorreu um erro durante o build do Maven. A copia foi abortada.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ========================================================
echo Build concluido. Copiando a pasta target para v:\rafael
echo ========================================================

if not exist "v:\rafael" mkdir "v:\rafael"

:: Copia a pasta target raiz, se existir
if exist "target\" (
    echo Copiando target da raiz...
    xcopy /E /I /Y "target" "v:\rafael\target"
)


echo.
echo Processo concluido com sucesso!
pause
