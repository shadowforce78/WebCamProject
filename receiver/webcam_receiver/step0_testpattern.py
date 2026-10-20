"""Étape 0 : envoie une mire animée dans la webcam virtuelle (PC seul)."""
import time

import cv2
import numpy as np
import pyvirtualcam

WIDTH, HEIGHT, FPS = 1280, 720, 30


def make_frame(n: int) -> np.ndarray:
    x = np.linspace(0, 255, WIDTH, dtype=np.uint8)
    frame = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
    frame[..., 0] = np.roll(x, n * 4)[None, :]
    frame[..., 1] = np.linspace(0, 255, HEIGHT, dtype=np.uint8)[:, None]
    frame[..., 2] = 128
    cv2.putText(frame, f"frame {n}", (40, 120), cv2.FONT_HERSHEY_SIMPLEX,
                3, (255, 255, 255), 6)
    return frame


def main() -> None:
    with pyvirtualcam.Camera(WIDTH, HEIGHT, FPS, fmt=pyvirtualcam.PixelFormat.RGB) as cam:
        print(f"Caméra virtuelle : {cam.device} ({WIDTH}x{HEIGHT}@{FPS})")
        n = 0
        while True:
            cam.send(make_frame(n))
            cam.sleep_until_next_frame()
            n += 1


if __name__ == "__main__":
    main()
