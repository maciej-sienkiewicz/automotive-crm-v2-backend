# Car Schematic Template

## Required File

This directory must contain a file named `car_schematic.jpg` which serves as the base template for generating damage maps.

### File Specifications

- **Filename**: `car_schematic.jpg`
- **Format**: JPG/JPEG
- **Recommended Dimensions**: 1200x800 pixels (or similar aspect ratio)
- **Content**: A top-down view of a car showing all sides (front, rear, left, right)
- **Color**: Any, but light backgrounds work best for red damage markers

### How It Works

When damage points are provided during vehicle check-in:

1. The system loads this template image
2. Damage points (with percentage coordinates) are converted to absolute pixel positions
3. Red circles (diameter: 30px) are drawn at each damage location
4. White text labels (damage point IDs) are centered in each circle
5. The resulting image is saved to AWS S3 at: `{studioId}/visits/{visitId}/damage-map.jpg`

### Creating the Template

You can create your own car schematic using:

- Vector graphics tools (Adobe Illustrator, Inkscape)
- CAD software
- Online car diagram generators
- Simple drawing tools (for prototyping)

**Note**: The template should show a clear outline of a car from a top-down perspective to make it easy for users to mark damage locations accurately.

### Example Template

If you don't have a custom template, you can use a placeholder:

1. Find a simple car outline image online (search for "car top view outline")
2. Ensure it's properly licensed for commercial use
3. Save it as `car_schematic.jpg` in this directory
4. Recommended size: 1200x800px or similar

### Troubleshooting

If you see an error like "Car schematic template not found", ensure:

1. The file is named exactly `car_schematic.jpg` (case-sensitive)
2. The file is in the `src/main/resources/images/` directory
3. The file is a valid JPG/JPEG image
4. The file is included in the build output (check `target/classes/images/` or `build/resources/main/images/`)
