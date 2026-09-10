import { useEffect, useState } from "react";

export function usePreloadImages(images: string[]) {
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!images || images.length === 0) {
      setLoading(false);
      return;
    }

    let loaded = 0;
    images.forEach((src) => {
      const img = new Image();
      img.src = src;
      img.onload = img.onerror = () => {
        loaded++;
        if (loaded === images.length) setLoading(false);
      };
    });
  }, [images]);

  return loading;
}
