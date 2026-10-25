/**
 * "Is this point in India?" for map pins, checked in the browser before the server checks it again.
 *
 * The shape (public/geo/india.json) is the same one the database uses (geo_regions 'IN'): Natural Earth 1:10m,
 * buffered 3 km for coasts and islands, simplified to about 2 km. It is only used for this check, never drawn.
 */

export interface LatLng {
  lat: number;
  lng: number;
}

/** GeoJSON MultiPolygon coordinates: polygons → rings (first is the outline, the rest are holes) → [lng, lat]. */
export type MultiPolygon = number[][][][];

/** India's bounding box with a small margin; the map cannot be panned outside it. */
export const INDIA_BOUNDS = { south: 6.4, west: 67.8, north: 35.8, east: 97.7 } as const;

/** Roughly the middle of India; the map opens here when no city is known. */
export const INDIA_CENTER: LatLng = { lat: 22.5, lng: 79.0 };

let shape: Promise<MultiPolygon | null> | null = null;

/** Loads the boundary once (about 22 KB). Resolves to null if it cannot be loaded; callers then use the box. */
export function loadIndia(): Promise<MultiPolygon | null> {
  shape ??= fetch('geo/india.json')
    .then((res) => (res.ok ? res.json() : null))
    .then((geo: { type?: string; coordinates?: MultiPolygon } | null) =>
      geo?.type === 'MultiPolygon' && Array.isArray(geo.coordinates) ? geo.coordinates : null,
    )
    .catch(() => null);
  return shape;
}

export function inIndiaBox(p: LatLng): boolean {
  return p.lat >= INDIA_BOUNDS.south && p.lat <= INDIA_BOUNDS.north && p.lng >= INDIA_BOUNDS.west && p.lng <= INDIA_BOUNDS.east;
}

/** True if the point is inside the shape (or, without a shape, inside India's box). */
export function inIndia(p: LatLng, india: MultiPolygon | null): boolean {
  if (!inIndiaBox(p)) return false;
  if (!india) return true;
  return india.some((polygon) => insideRing(p, polygon[0]) && !polygon.slice(1).some((hole) => insideRing(p, hole)));
}

/** Ray casting (even-odd rule). Rings are [lng, lat] pairs. */
function insideRing(p: LatLng, ring: number[][]): boolean {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if (yi > p.lat !== yj > p.lat && p.lng < ((xj - xi) * (p.lat - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

/** Straight-line distance in km (haversine). */
export function distanceKm(a: LatLng, b: LatLng): number {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * 6371.0088 * Math.asin(Math.sqrt(h));
}
