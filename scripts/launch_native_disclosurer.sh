#!/bin/bash

. ./common.sh --source-only

while getopts f:p:r option
do
    case "${option}"
        in
        f) FILE=${OPTARG};;
    esac
done

if [ -z "$FILE" ]
then
    echo
    read -p "APK path: " FILE
fi

if [ ! -f "$FILE" ]
then
    end_program "$FILE does not exist"
fi

DST=$(dirname $FILE)/
./execute_with_limit_time.sh python3 main.py $FILE --out $DST
check_return $? "Something went wront with nativedisclosurer" ""

LOC=$DST/$(basename $FILE .apk)"_result"
if [ $(ls $LOC/|grep ".result$" |wc -l) -gt 0 ]
then
    for f in $LOC/*result
    do
        tail -n +2 $f|awk -F, '{print $4}'|tr -d " " > $f.entrypoints
    done
fi
