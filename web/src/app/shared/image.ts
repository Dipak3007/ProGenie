/**
 * Shrinks a photo in the browser before upload (Phase 3A-8): the longest side becomes at most {@code maxPx}
 * and it is saved as JPEG. A 12 MP phone photo (4–8 MB) becomes roughly 200–500 KB, which uploads quickly on a
 * mobile connection. The photo's orientation (EXIF) is applied by the browser when decoding.
 *
 * Falls back to the original file when the browser cannot decode it (the API then checks it as usual).
 */
export async function shrinkImage(file: File, maxPx = 1600, quality = 0.82): Promise<File> {
  if (!file.type.startsWith('image/') || typeof createImageBitmap !== 'function') return file;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    return file;
  }
  try {
    const scale = Math.min(1, maxPx / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) return file;
    ctx.fillStyle = '#ffffff'; // transparent PNG areas become white, not black, in JPEG
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(bitmap, 0, 0, width, height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
    if (!blob || (scale === 1 && blob.size >= file.size && file.type === 'image/jpeg')) return file;
    const name = file.name.replace(/\.[^.]+$/, '') + '.jpg';
    return new File([blob], name, { type: 'image/jpeg', lastModified: Date.now() });
  } finally {
    bitmap.close();
  }
}
