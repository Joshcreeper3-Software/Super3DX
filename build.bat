@echo off
echo === Super3DX Build Script ===
echo.

REM Step 1: Compile engine
echo Compiling engine...
javac -encoding UTF-8 Super3DX.java
if %errorlevel% neq 0 (
    echo Compilation failed!
    exit /b 1
)
echo Engine compiled.

REM Step 2: Package library JAR
echo Creating Super3DX.jar...
jar cf Super3DX.jar Super3DX.class Super3DX$*.class
if %errorlevel% neq 0 (
    echo Failed to create Super3DX.jar
    exit /b 1
)
echo Super3DX.jar created!
echo.

REM Step 3: Compile test against the JAR
echo Compiling test...
javac -cp Super3DX.jar -encoding UTF-8 Super3DXTest.java
if %errorlevel% neq 0 (
    echo Test compilation failed!
    exit /b 1
)
echo Test compiled.

REM Step 4: Clean up engine class files (not the test class)
del Super3DX.class
del Super3DX$*.class

REM Step 5: Create manifest for test JAR
echo Main-Class: Super3DXTest> manifest.txt
echo Class-Path: Super3DX.jar>> manifest.txt
echo.>> manifest.txt

REM Step 6: Package test JAR (only test class + manifest, references Super3DX.jar)
echo Creating Super3DX-Test.jar...
jar cfm Super3DX-Test.jar manifest.txt Super3DXTest*.class
if %errorlevel% neq 0 (
    echo Failed to create Super3DX-Test.jar
    del manifest.txt
    exit /b 1
)
echo Super3DX-Test.jar created!

REM Step 7: Clean up
del manifest.txt
del Super3DXTest*.class

echo.
echo === Build complete! ===
echo.
echo Library: Super3DX.jar
echo Test:    Super3DX-Test.jar
echo.
echo Run test: java -jar Super3DX-Test.jar
echo.
echo To use in your project, add Super3DX.jar to your classpath:
echo   javac -cp Super3DX.jar YourGame.java
echo   java -cp Super3DX.jar;. YourGame
