#!/bin/bash
#
# Import several README.md files from the GeoMesa tutorials distribution,
# using ``pandoc`` to convert Markdown to RST.
#
# ----------------------------------------------------------------------
# WARNING: This is not completely automated. You will almost certainly
# have to hand-edit the files after this import!
# ----------------------------------------------------------------------

# default paths
# may be overridden by setting variables or by command line switches
# (will try to guess $GEOMESA relative to the script path)
GEOMESA=${GEOMESA:-$(readlink -f "$(dirname ${BASH_SOURCE[0]})/../..")}
GEOMESA_TUTORIALS=${GEOMESA_TUTORIALS:-$GEOMESA/../geomesa-tutorials}

function usage {
    echo "$0: imports several README.md files from the GeoMesa tutorials distribution,"
    echo "using pandoc to convert Markdown to RST."
    echo
    echo "usage: $0 [options] [<tutorials-to-copy>]"
    echo
    echo "If <tutorials-to-copy> is omitted, all tutorials will be copied."
    echo
    echo "options:"
    echo "  --gm <dir>     set \$GEOMESA [$GEOMESA]"
    echo "  --tut <dir>    set \$GEOMESA_TUTORIALS [$GEOMESA_TUTORIALS]"
}

# parse options
while :
do
   case "$1" in
       --gm)
           GEOMESA="$2"
           shift 2
           ;;
       --tut)
           GEOMESA_TUTORIALS="$2"
           shift 2
           ;;
       -h | --help)
           usage
           exit 1
           ;;
       --) # manual end of options
           break
           ;;
       -*)
           echo "ERROR: unknown option $1" >&2
           exit 2
           ;;
       *) # end of options
           break
           ;;
   esac
done

#
if [ "$#" -eq 0 ] ; then
    TUTORIALS="
geomesa-examples-authorizations
geomesa-examples-avro
geomesa-examples-featurelevelvis
geomesa-examples-gdelt
geomesa-examples-transformations
geomesa-quickstart-accumulo
geomesa-quickstart-hbase
geomesa-quickstart-kafka
geomesa-quickstart-nifi
geomesa-quickstart-storm
geomesa-quickstart-cassandra
geomesa-quickstart-lambda
    "
else
    TUTORIALS="$@"
fi

# load functions
source $GEOMESA/docs/bin/common.sh
if [[ "$?" != 0 ]] ; then
    echo "ERROR: can't source common.sh"
    exit 1
fi

# check repos
check_repo $GEOMESA git@github.com:locationtech/geomesa.git
check_repo $GEOMESA_TUTORIALS git@github.com:geomesa/geomesa-tutorials.git

#
for tutorial in $TUTORIALS ; do
    echo "## $tutorial:"

    # copy the README.md file
    text_src=$GEOMESA_TUTORIALS/${tutorial}/README.md
    text_dst=$GEOMESA/docs/tutorials/${tutorial}.rst
    echo "Converting $text_src => $text_dst"
    pandoc -i $text_src -o $text_dst

    # copy the assets too, if they exist
    asset_src=$GEOMESA_TUTORIALS/assets/${tutorial}
    asset_dst=$GEOMESA/docs/tutorials/_static/${tutorial}
    if [[ -d $asset_src ]] ; then
        echo "Copying $asset_src/* => $asset_dst"
	    mkdir -p $asset_dst
	    cp $asset_src/* $asset_dst
    fi

    # fix links and bad RST generated by pandoc
    echo "Fixing links and bad RST"
    sed -i -e 's/.. code::/.. code-block::/' $text_dst
    sed -i -e 's!../assets/!_static/!' $text_dst
    sed -i -e "s/    :warning: Note: /.. note::\n\n    /" $text_dst
    sed -i -e "s/    :warning: /.. warning::\n\n    /" $text_dst
done
