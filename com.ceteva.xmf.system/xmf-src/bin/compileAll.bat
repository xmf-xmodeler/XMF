@echo off

set XMFHOME=%1%
set LIB=%XMFHOME%;%XMFHOME%/../../com.ceteva.xmf.machine/bin
set EVALUATOR=%XMFHOME%/../xmf-img/compiler.img
set HEAPSIZE=5000
set STACKSIZE=50
set FREEHEAP=20
set FILENAME=%2%
set MAXJAVAHEAP=-Xmx200m
set MAXJAVASTACK=-Xss5m
set VERSION=2.2
set JAVA=java
set LOCALE=-Duser.country=UK
set BOOTFILE=%XMFHOME%/Boot/CompileAll.o

%JAVA% %LOCALE% %MAXJAVAHEAP% -cp %LIB% xos.OperatingSystem -image %EVALUATOR% -heapSize %HEAPSIZE% -heapSize %HEAPSIZE% -stackSize %STACKSIZE% -arg filename:%BOOTFILE% -arg user:"%USERNAME%" -arg home:"%XMFHOME%" -arg license:license.lic -arg version:"%VERSION%" %2 %3 %4 %5 %6 %7 %8 %9






































