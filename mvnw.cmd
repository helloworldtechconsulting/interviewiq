@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@REM ----------------------------------------------------------------------------

@IF "%__MVNW_ARG0_NAME__%"=="" (SET "BASE_DIR=%~dp0") ELSE (SET "BASE_DIR=%__MVNW_ARG0_NAME__%")

@SET MAVEN_PROJECTBASEDIR=%MAVEN_BASEDIR%
@IF NOT "%MAVEN_PROJECTBASEDIR%"=="" GOTO endDetectBaseDir

@SET EXEC_DIR=%CD%
@SET WDIR=%EXEC_DIR%
:findBaseDir
@IF EXIST "%WDIR%"\.mvn GOTO baseDirFound
@cd ..
@IF "%WDIR%"=="%CD%" GOTO baseDirNotFound
@SET "WDIR=%CD%"
@GOTO findBaseDir

:baseDirFound
@SET MAVEN_PROJECTBASEDIR=%WDIR%
@CD /D "%EXEC_DIR%"
@GOTO endDetectBaseDir

:baseDirNotFound
@SET MAVEN_PROJECTBASEDIR=%EXEC_DIR%

:endDetectBaseDir
@IF NOT EXIST "%MAVEN_PROJECTBASEDIR%\.mvn\jvm.config" GOTO endReadJvmConfig
@setlocal EnableExtensions EnableDelayedExpansion
@for /F "usebackq delims=" %%a in ("%MAVEN_PROJECTBASEDIR%\.mvn\jvm.config") do @set JVM_CONFIG_MAVEN_PROPS=!JVM_CONFIG_MAVEN_PROPS! %%a
@endlocal & SET JVM_CONFIG_MAVEN_PROPS=%JVM_CONFIG_MAVEN_PROPS%
:endReadJvmConfig

@SET WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
@SET WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain
@SET WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"

@IF EXIST %WRAPPER_JAR% GOTO skipDownload
@ECHO Downloading Maven Wrapper from %WRAPPER_URL%
@powershell -Command "&{"^
  "$webclient = new-object System.Net.WebClient;"^
  "if (-not ([string]::IsNullOrEmpty('%MVNW_USERNAME%') -and [string]::IsNullOrEmpty('%MVNW_PASSWORD%'))) {"^
  "$webclient.Credentials = new-object System.Net.NetworkCredential('%MVNW_USERNAME%', '%MVNW_PASSWORD%');"^
  "}"^
  "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12;"^
  "$webclient.DownloadFile('%WRAPPER_URL%', %WRAPPER_JAR%)"^
  "}"
@IF "%MVNW_VERBOSE%"=="true" @ECHO Finished downloading %WRAPPER_JAR%

:skipDownload
@IF "%MVNW_VERBOSE%"=="true" (
    @ECHO MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR%
    @ECHO JAVA_HOME=%JAVA_HOME%
    @ECHO MAVEN_OPTS=%MAVEN_OPTS%
    @ECHO classpath=%WRAPPER_JAR%
)

@java %JVM_CONFIG_MAVEN_PROPS% %MAVEN_OPTS% %MAVEN_DEBUG_OPTS% -classpath %WRAPPER_JAR% "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" %WRAPPER_LAUNCHER% %MAVEN_CONFIG% %*

@IF ERRORLEVEL 1 GOTO error
@GOTO end

:error
@SET ERROR_CODE=1

:end
@ENDLOCAL & SET ERROR_CODE=%ERROR_CODE%

@IF NOT "%SHLVL%"=="" (
    @EXIT %ERROR_CODE%
)

cmd /C exit /B %ERROR_CODE%
