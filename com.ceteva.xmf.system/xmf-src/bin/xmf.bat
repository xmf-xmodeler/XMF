@echo off

set XMFHOME=%1%
set LIB=%XMFHOME%;%XMFHOME%/../../com.ceteva.xmf.machine/bin
set PORT=100
set XMFIMAGE=%XMFHOME%/../xmf-img/xmf.img
set HEAPSIZE=2250
set MAXJAVAHEAP=-Xmx200m

echo [ bin/xmf %* ]

java %MAXJAVAHEAP% -cp %LIB% xos.OperatingSystem -port %PORT% -image %XMFIMAGE% -heapSize %HEAPSIZE% -arg user:"%USERNAME%" -arg home:%XMFHOME% -arg license:license.lic -arg filename:%2 %*
 
