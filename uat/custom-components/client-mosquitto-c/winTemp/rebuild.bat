@REM that required to make paths shorter

SET cwd=%cd%
SET buildroot=D:\u\cc

mkdir %buildroot%

mklink /J %buildroot%\mqt %cwd%
mklink /J %buildroot%\proto %cwd%\..\..\proto
cd %buildroot%\mqt

@REM call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" x86_amd64


@REM cmake -DCMAKE_SYSTEM_VERSION=10.0.226621.0 -Bbuild -H.

@REM nmake build
@REM cmake -DCMAKE_BUILD_TYPE=Debug  -Bbuild -H.
@REM cmake --build build --target all


@REM ninja build
cmake -DCMAKE_BUILD_TYPE=Debug -G "Ninja" -Bbuild -H.
cmake --build build -j %NUMBER_OF_PROCESSORS% --target all

