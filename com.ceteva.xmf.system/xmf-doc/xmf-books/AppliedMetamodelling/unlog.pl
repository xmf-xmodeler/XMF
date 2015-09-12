#!/usr/bin/perl

die "unlog logfile output-file" unless $#ARGV == 1;

($file,$output) = @ARGV;

if (!($file =~ /\.log$/)) {
    $file .= ".log";
}

die "cannot read $file" unless -r $file;

open(LOGFILE,"$file");

chomp($pwd = `pwd`);

while (<LOGFILE>) {
    while (/\((\S*\.(bbl|tex|sty|cls|e?ps|tib|ttz|ttx))\b/g) {
	print "found $1 ";
	# Convert relative directories to absolute in the tar args:
	$x=$1;
	($dir,$name) = ($x =~ m/(.*\/)?([^\/]*)/);
	print "dir $dir file $name\n";
	if ($dir) {
	    $args .= "-C $dir $name ";
	}
	else {
	    $args .= "-C $pwd $name ";
	}
    }
}

print "tar cf $output $args\n";
system("tar cf $output $args");
