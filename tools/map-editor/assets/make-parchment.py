# Paints the default parchment tile (1236 x 1575 px, seamless top to bottom). Run: python3 make-parchment.py
import numpy as np
from PIL import Image
W,H=1236,1575
rng=np.random.default_rng(7)
def pnoise(scale, power=2.0):
    # periodic noise via FFT filtering
    f=np.fft.fft2(rng.standard_normal((H,W)))
    ky=np.fft.fftfreq(H)[:,None]*H; kx=np.fft.fftfreq(W)[None,:]*W
    k=np.sqrt((kx/W)**2+(ky/H)**2)*1000+1e-6
    filt=1.0/(1+(k/scale)**power)
    n=np.real(np.fft.ifft2(f*filt))
    n=(n-n.mean())/n.std(); return n
low=pnoise(3,2.5); mid=pnoise(25,2); hi=pnoise(250,1.5)
# fibres: anisotropic noise stretched horizontally
f=np.fft.fft2(rng.standard_normal((H,W)))
ky=np.fft.fftfreq(H)[:,None]; kx=np.fft.fftfreq(W)[None,:]
fib=np.real(np.fft.ifft2(f*np.exp(-((kx*25)**2+(ky*4)**2)*40))); fib=(fib-fib.mean())/fib.std()
# stains: thresholded low noise
st=pnoise(6,3); stain=np.clip((st-1.9)*0.9,0,1)
ring=np.clip(1-np.abs(st-1.95)*7,0,1)*0.5
base=np.array([236,221,190],float)
dark=np.array([196,165,120],float)
mix=np.clip(0.18+0.22*low+0.08*mid,0,1)[...,None]
img=base*(1-mix)+dark*mix
img=img*(1-0.10*stain[...,None])-ring[...,None]*np.array([18,30,46])
img+=(hi*4+fib*1.2)[...,None]
# side edges darken (width is fixed, vertical stays seamless)
x=np.linspace(-1,1,W)[None,:]
edge=np.clip((np.abs(x)-0.82)/0.18,0,1)**2
img=img*(1-0.22*edge[...,None])
img=np.clip(img,0,255).astype(np.uint8)
Image.fromarray(img).save('parchment.webp',quality=88)
