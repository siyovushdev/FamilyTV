#!/usr/bin/env python3
"""Rebuild the five local 90s preview motion fixtures from their bundled Stitch artwork.
Requires ffmpeg. This is debug playback test content, not finished FamilyTV episodes.
Does not download artwork, change the catalog, or initialize voice models.
"""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'app/src/debug/assets/stitch_watch'
for name in ['saturn','comet','moon','whale','nature']:
    command=['ffmpeg','-v','error','-loop','1','-i',str(BASE/'art'/f'{name}.webp'),
        '-f','lavfi','-i','sine=frequency=220:sample_rate=24000:duration=90',
        '-vf',"scale=1280:720,zoompan=z='1.015+0.012*sin(on/170)':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=1:s=1280x720:fps=24,format=yuv420p",
        '-af','volume=0.035,afade=t=in:d=3,afade=t=out:st=87:d=3','-t','90',
        '-c:v','libx264','-preset','fast','-crf','27','-profile:v','main','-pix_fmt','yuv420p',
        '-c:a','aac','-b:a','32k','-movflags','+faststart','-y',str(BASE/'media'/f'{name}.mp4')]
    subprocess.run(command,check=True)
    print(name,flush=True)
