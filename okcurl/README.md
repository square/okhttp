OkCurl
======

_A curl for the next-generation web._

OkCurl is an OkHttp-backed curl clone which allows you to test OkHttp's HTTP engine (including
SPDY and HTTP/2) against web servers.

    $ ./okcurl/okcurl --frames https://graph.facebook.com/robots.txt
    << 0x00000000    30 SETTINGS      
    >> 0x00000000     0 SETTINGS      ACK
    << 0x00000000     4 WINDOW_UPDATE 
    >> CONNECTION 505249202a20485454502f322e300d0a0d0a534d0d0a0d0a
    >> 0x00000000     6 SETTINGS      
    >> 0x00000000     4 WINDOW_UPDATE 
    >> 0x00000003    76 HEADERS       END_STREAM|END_HEADERS
    << 0x00000000    34 GOAWAY        
    >> CONNECTION 505249202a20485454502f322e300d0a0d0a534d0d0a0d0a
    >> 0x00000000     6 SETTINGS      
    >> 0x00000000     4 WINDOW_UPDATE 
    >> 0x00000003    76 HEADERS       END_STREAM|END_HEADERS
    << 0x00000000    30 SETTINGS      
    >> 0x00000000     0 SETTINGS      ACK
    << 0x00000000     4 WINDOW_UPDATE 
    << 0x00000000     0 SETTINGS      ACK
    << 0x00000003     4 WINDOW_UPDATE 
    << 0x00000003   173 HEADERS       END_HEADERS
    << 0x00000003    26 DATA          END_STREAM
    User-agent: *
    Disallow: /
    >> 0x00000000     8 GOAWAY        
    
