#!/usr/bin/env python3
"""Bounded physical microphone capture with PulseAudio's input-latency estimates.

Records mono PCM16 at 48 kHz. Each chunk has a UTC end-frame estimate from the
host clock minus pa_simple_get_latency(), plus query duration for auditing.
Requires a synchronized host clock and a physical default source (not a monitor).
Recordings may include ambient audio; keep the output private.
"""
import argparse
import ctypes as C
import ctypes.util
import json
from pathlib import Path
import signal
import time
import wave


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wav',type=Path,required=True)
    parser.add_argument('--seconds',type=int,default=180)
    args=parser.parse_args()
    if not 1<=args.seconds<=240: parser.error('Capture deadline must be 1–240 seconds.')
    class Spec(C.Structure):
        _fields_=[('format',C.c_int),('rate',C.c_uint32),('channels',C.c_uint8)]
    class Attr(C.Structure):
        _fields_=[(name,C.c_uint32) for name in ['maxlength','tlength','prebuf','minreq','fragsize']]
    lib=C.CDLL(ctypes.util.find_library('pulse-simple'))
    lib.pa_simple_new.argtypes=[C.c_char_p,C.c_char_p,C.c_int,C.c_char_p,C.c_char_p,C.POINTER(Spec),C.c_void_p,C.POINTER(Attr),C.POINTER(C.c_int)]
    lib.pa_simple_new.restype=C.c_void_p
    lib.pa_simple_read.argtypes=[C.c_void_p,C.c_void_p,C.c_size_t,C.POINTER(C.c_int)]
    lib.pa_simple_read.restype=C.c_int
    lib.pa_simple_get_latency.argtypes=[C.c_void_p,C.POINTER(C.c_int)]
    lib.pa_simple_get_latency.restype=C.c_uint64
    lib.pa_simple_free.argtypes=[C.c_void_p]
    spec=Spec(3,48000,1)  # PA_SAMPLE_S16LE / PA_STREAM_RECORD.
    attr=Attr(192000,0xffffffff,0xffffffff,0xffffffff,4800)
    error=C.c_int()
    stream=lib.pa_simple_new(None,b'8CBdroid timing check',2,None,b'Physical microphone',C.byref(spec),None,C.byref(attr),C.byref(error))
    if not stream: raise RuntimeError('PulseAudio capture failed: '+str(error.value))
    stop=False
    def stopping(*_):
        nonlocal stop
        stop=True
    signal.signal(signal.SIGTERM,stopping);signal.signal(signal.SIGINT,stopping)
    buffer=C.create_string_buffer(4800);frames=0;deadline=time.monotonic()+args.seconds
    try:
        with wave.open(str(args.wav),'wb') as wav, args.wav.with_suffix('.timestamps.jsonl').open('w') as stamps:
            wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(48000)
            while not stop and time.monotonic()<deadline:
                if lib.pa_simple_read(stream,buffer,len(buffer),C.byref(error))<0: raise RuntimeError('PulseAudio read failed: '+str(error.value))
                before=time.time_ns();mono_before=time.monotonic_ns()
                latency=lib.pa_simple_get_latency(stream,C.byref(error))
                after=time.time_ns();mono_after=time.monotonic_ns()
                if latency==0xffffffffffffffff: raise RuntimeError('Input latency unavailable: '+str(error.value))
                if latency>2_000_000: raise RuntimeError('Input capture latency exceeded two seconds.')
                wav.writeframesraw(buffer.raw);frames+=2400
                stamps.write(json.dumps(dict(end_frame=frames,end_utc_ns=(before+after)//2-latency*1000,
                    input_latency_us=latency,query_span_ns=mono_after-mono_before,
                    wall_minus_monotonic_ns=after-mono_after))+'\n')
                if frames==2400: args.wav.with_suffix('.ready.json').write_text(json.dumps(dict(rate_hz=48000)))
    finally: lib.pa_simple_free(stream)


if __name__=='__main__':main()
