# Compile XMF.system
## Prerequisites
Begin by compiling all the required files. If you skip these steps, every other script in /bin will fail, since they're depend on this script's output.

### Step 1
Open Mosaic, select 'File' -> 'Open File Browser ...' -> select /xmf-src -> 'Open'.

### Step 2
Scroll to '/Manifest.xmf' in the file tree, right click -> 'Compile File'.

### Step 3
Scroll to '/Manifest.xmf' in the file tree, right click -> 'Manifest' -> 'Build'.

### Step 4
Make sure you are in the xmf source directory (`cd ./xmf-src`). Every script in /bin expects you to be in this directory.

`./bin/compileAll .`

Don't forget the current directory as the second argument.

### Step 5

Now you can compile every components with the scripts at in /bin. See [usage](bin/README.md) for more info.
