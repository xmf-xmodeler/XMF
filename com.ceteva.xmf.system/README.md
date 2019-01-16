# Compile XMF.system
## Prerequisites
Begin by compiling all the required files. If you skip these steps, every other script in /bin will fail, since they're depend on this script's output.

### Step 1
Open Mosaic, select 'File' -> 'Open' -> select /xmf-src -> 'Open'.

### Step 2
Right click on '/xmf-src' in the file tree -> 'compile'.

### Step 3
Make sure you are in the xmf source directory (`cd ./xmf-src`). Every script in /bin expects you to be in this directory.

`./bin/compileAll .`

### Step 3

`./bin/makexmf .`

##[Usage](bin/README.md)