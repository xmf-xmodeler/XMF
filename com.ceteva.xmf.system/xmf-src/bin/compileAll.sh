#!/bin/sh
set -x
XMFHOME=$1
LIB="$XMFHOME:$XMFHOME/../../com.ceteva.xmf.machine/bin"
EVALUATOR=$XMFHOME/../xmf-img/compiler.img
HEAPSIZE=5000
STACKSIZE=50
FREEHEAP=20
FILENAME=$2
MAXJAVAHEAP="-Xmx200m"
MAXJAVASTACK="-Xss5m"
VERSION=2.2
JAVA=java
LOCALE="-Duser.country=UK"
BOOTFILE=$XMFHOME/Boot/CompileAll.o

$JAVA $LOCALE $MAXJAVAHEAP -cp $LIB xos.OperatingSystem -image $EVALUATOR -heapSize $HEAPSIZE -stackSize $STACKSIZE -arg filename:$BOOTFILE -arg user:"USERNAME" -arg home:$XMFHOME -arg license:license.lic -arg version:$VERSION 






































