from PIL import Image, ImageDraw, ImageFilter, ImageChops
import numpy as np, json, sys
R='docs/design/stitch/explore-weather-v1/'
orig=Image.open(R+'android-preview/reference/weather_intro_clean.png').convert('RGB')
gen=Image.open(R+'ux-polish/reference/weather_intro_one_lumi_candidate.png').convert('RGB')
W,H=orig.size
poly=[(1335,316),(1482,316),(1502,356),(1514,470),(1516,645),(1478,712),(1338,712),(1338,560),(1300,572),(1212,560),(1212,482),(1301,482),(1301,356)]
mask=Image.new('L',(W,H),0); ImageDraw.Draw(mask).polygon(poly,fill=255)
# Alignment band around the mask only.
band=np.asarray(mask.filter(ImageFilter.MaxFilter(61)))>0
band&=~(np.asarray(mask.filter(ImageFilter.MaxFilter(21)))>0)
O=np.asarray(orig).astype(np.float32)
x0,y0,x1,y1=1150,250,1600,780
Osub=O[y0:y1,x0:x1]; B=band[y0:y1,x0:x1]
G=np.asarray(gen.resize((round(W*.995),round(H*.995)),Image.BICUBIC)).astype(np.float32)
ox=round(x0*.995)-x0+14; oy=round(y0*.995)-y0-2
best=(float(np.abs(G[oy+y0:oy+y1,ox+x0:ox+x1]-Osub).mean(axis=2)[B].mean()),.995,.995,14,-2)
print('band alignment',best)
d,sx,sy,dx,dy=best
gw,gh=round(W*sx),round(H*sy)
G=gen.resize((gw,gh),Image.BICUBIC)
ox=round(x0*sx)-x0+dx; oy=round(y0*sy)-y0+dy
aligned=Image.new('RGB',(W,H)); aligned.paste(G,(-ox,-oy))
# Hard edge next to the big Lumi's fingers, soft elsewhere.
soft=mask.filter(ImageFilter.GaussianBlur(9))
hard=mask.filter(ImageFilter.GaussianBlur(1.5))
S=np.asarray(soft).astype(np.float32); Hh=np.asarray(hard).astype(np.float32)
ys,xs=np.mgrid[0:H,0:W]
near_lumi=(xs<1322)&(ys<500)
alpha=np.where(near_lumi,Hh,S)
alpha=Image.fromarray(alpha.clip(0,255).astype(np.uint8))
out=Image.composite(aligned,orig,alpha)
out.save(R+'ux-polish/reference/weather_intro_one_lumi.png')
alpha.save('/tmp/alpha.png')
diff=np.abs(np.asarray(out).astype(int)-np.asarray(orig).astype(int)).sum(axis=2)>0
yy,xx=np.nonzero(diff)
info={'alignment':{'meanAbsBandDiff':round(d,2),'scaleX':sx,'scaleY':sy,'offsetX':ox,'offsetY':oy},'maskPolygon':poly,
 'changedBBox':[int(xx.min()),int(yy.min()),int(xx.max()),int(yy.max())],'changedPixels':int(diff.sum()),'totalPixels':W*H}
print(json.dumps(info))
json.dump(info,open('/tmp/composite.json','w'))
c=out.crop((1150,250,1600,780)); o=orig.crop((1150,250,1600,780))
side=Image.new('RGB',(900,530)); side.paste(o,(0,0)); side.paste(c,(450,0)); side.save('/tmp/side.png')
