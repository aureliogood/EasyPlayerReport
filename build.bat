@echo off
setlocal enabledelayedexpansion
rem ==============================================================================
rem  EasyPlayerReport - compila, pasa las pruebas, ofusca y empaqueta.
rem
rem  Como se construye:
rem    1. Maven compila y pasa las pruebas unitarias (si una falla, no hay JAR).
rem    2. ProGuard renombra las clases para que el JAR no sea comodo de reusar.
rem    3. El JAR sale en target\ y se copia a dist\plugins\ para entregarlo.
rem
rem  Requisitos: un JDK 21. Con el JDK 25 el ofuscado falla, porque ProGuard no
rem  sabe leer los class files de la version 69. Si tienes varios JDK, se usa el
rem  21 automaticamente; si no, pon JAVA_HOME a mano antes de ejecutar esto.
rem ==============================================================================
set BASE=%~dp0

rem --- Maven: primero el de la carpeta, luego el del sistema, y si no esta,
rem --- se descarga. Asi el proyecto se puede clonar y compilar sin instalar nada.
set MVN=%BASE%apache-maven-3.9.9\bin\mvn.cmd
if not exist "%MVN%" (
  for /f "delims=" %%i in ('where mvn.cmd 2^>nul') do if not defined MVN_FROM_PATH set MVN_FROM_PATH=%%i
  if defined MVN_FROM_PATH set MVN=%MVN_FROM_PATH%
)
if "%MVN%"=="%BASE%apache-maven-3.9.9\bin\mvn.cmd" if not exist "%MVN%" (
  echo [INFO] No hay Maven en la carpeta ni en el PATH. Descargando...
  curl.exe -sL -o "%BASE%maven.zip" "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip"
  powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('%BASE%maven.zip', '%BASE%')"
  set MVN=%BASE%apache-maven-3.9.9\bin\mvn.cmd
)

rem --- Java 21. Solo se pisa JAVA_HOME si el JDK 21 esta donde se espera, para
rem --- no romper a quien ya tenga el suyo bien configurado.
if not exist "%JAVA_HOME%\bin\java.exe" if exist "C:\Program Files\Java\jdk-21.0.11\bin\java.exe" (
  set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11
)
if defined JAVA_HOME echo [INFO] JDK: %JAVA_HOME%

echo [1/3] Compilando, pasando pruebas y ofuscando... (la primera vez tarda)
rem Sin -DskipTests a proposito: si una prueba falla, no se genera el JAR.
call "%MVN%" -f "%BASE%pom.xml" clean package
if errorlevel 1 (
  echo [ERROR] La compilacion fallo. No se ha generado ningun JAR.
  exit /b 1
)

echo [2/3] Copiando a dist\plugins\EasyPlayerReport.jar
if not exist "%BASE%dist\plugins" mkdir "%BASE%dist\plugins"
copy /Y "%BASE%target\EasyPlayerReport.jar" "%BASE%dist\plugins\EasyPlayerReport.jar" >nul
if exist "%BASE%README.md" copy /Y "%BASE%README.md" "%BASE%dist\README.md" >nul
if exist "%BASE%LICENSE" copy /Y "%BASE%LICENSE" "%BASE%dist\LICENSE" >nul

echo [3/3] Empaquetando el ZIP de entrega en la carpeta del proyecto
set ZIP=%BASE%EasyPlayerReport.zip
if exist "%ZIP%" del /Q "%ZIP%"
powershell -NoProfile -Command "Compress-Archive -Path '%BASE%dist\*' -DestinationPath '%ZIP%' -Force"

echo.
echo [OK] Jar: %BASE%dist\plugins\EasyPlayerReport.jar
echo [OK] Zip: %ZIP%
rem target\proguard_map.txt traduce los nombres ofuscados a los originales.
rem Sirve para leer un stack trace de produccion, pero es justo la pieza que
rem deshace el ofuscado: esta en target\, que esta en .gitignore, y nunca debe
rem subirse a ningun repositorio ni entrar en el ZIP de entrega.
echo [AVISO] No publiques nunca target\proguard_map.txt
endlocal