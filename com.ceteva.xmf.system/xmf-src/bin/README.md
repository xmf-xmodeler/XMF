## Usage
### compileAll
`./bin/compileAll .`

Compiles all the files necessary for using any other script in /bin 

### compiler
`./bin/compiler .`
Starts the compiler image. This starts the VM with a large heap and all the definitions loaded in.
Contains the top-level command loop.

### makecompiler
`./bin/makecompiler .`

Used to create the compiler image. Images are held in ../xmf-img/.

### makemosaic
`./bin/makemosaic .`

Builds new system images in ../xmf-img/ used in conjunction with makexmf. 

<!--TODO://-->

### makexmf
`./bin/makexmf .`

Used to make the minimal XCore image. Images are held in ../xmf-img/.

### xmf.bat
`./bin/xmf.bat .`


Starts the minimal XCore image with a very small heap.