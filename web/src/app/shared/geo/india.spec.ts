import { MultiPolygon, distanceKm, inIndia, inIndiaBox } from './india';

// A square "country" with a square hole, in [lng, lat] like GeoJSON.
const SHAPE: MultiPolygon = [
  [
    [[70, 10], [90, 10], [90, 30], [70, 30], [70, 10]],
    [[78, 18], [82, 18], [82, 22], [78, 22], [78, 18]],
  ],
];

describe('India boundary check', () => {
  it('uses the outline and its holes', () => {
    expect(inIndia({ lat: 12, lng: 72 }, SHAPE)).toBe(true);
    expect(inIndia({ lat: 20, lng: 80 }, SHAPE)).toBe(false); // in the hole
    expect(inIndia({ lat: 32, lng: 80 }, SHAPE)).toBe(false); // in the box, outside the outline
  });

  it('refuses anything outside India’s box, with or without the shape', () => {
    expect(inIndia({ lat: 51.5, lng: -0.12 }, SHAPE)).toBe(false); // London
    expect(inIndia({ lat: 51.5, lng: -0.12 }, null)).toBe(false);
    expect(inIndiaBox({ lat: 23.02, lng: 72.57 })).toBe(true); // Ahmedabad
    expect(inIndia({ lat: 23.02, lng: 72.57 }, null)).toBe(true);
  });

  it('measures straight-line distance', () => {
    const km = distanceKm({ lat: 23.0225, lng: 72.5714 }, { lat: 22.3072, lng: 73.1812 }); // Ahmedabad → Vadodara
    expect(km).toBeGreaterThan(95);
    expect(km).toBeLessThan(105);
  });
});
