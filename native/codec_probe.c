/* Host-only comparison of the upstream text codec; never linked into Android. */
#include "ft8/message.h"
#include <stdio.h>
int main(int argc,char** argv) {
    if(argc!=2)return 2;
    ftx_message_t message={0};
    int status=ftx_message_encode(&message,NULL,argv[1]);
    printf("%d|",status);
    for(int i=0;i<77;++i)putchar('0'+((message.payload[i/8]>>(7-i%8))&1));
    putchar('\n');return 0;
}
