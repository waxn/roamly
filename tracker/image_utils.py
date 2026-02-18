import io
import uuid
from PIL import Image
from django.core.files.uploadedfile import InMemoryUploadedFile


def resize_image(image_field, max_size, quality=85):
    """Resize an image to fit within max_size x max_size square, return InMemoryUploadedFile."""
    img = Image.open(image_field)
    img = img.convert('RGB')

    # Center-crop to square
    w, h = img.size
    if w != h:
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        img = img.crop((left, top, left + side, top + side))

    img.thumbnail((max_size, max_size), Image.LANCZOS)

    buffer = io.BytesIO()
    img.save(buffer, format='JPEG', quality=quality)
    buffer.seek(0)

    filename = f'{uuid.uuid4().hex[:12]}_{max_size}.jpg'
    return InMemoryUploadedFile(
        buffer, 'ImageField', filename,
        'image/jpeg', buffer.getbuffer().nbytes, None
    )


def resize_photo(image_field, max_width=1200, thumb_size=300, quality=85):
    """Resize a photo for display and generate thumbnail. Returns (full, thumb)."""
    img = Image.open(image_field)
    img = img.convert('RGB')

    # Full size
    if img.width > max_width:
        ratio = max_width / img.width
        img = img.resize((max_width, int(img.height * ratio)), Image.LANCZOS)

    full_buf = io.BytesIO()
    img.save(full_buf, format='JPEG', quality=quality)
    full_buf.seek(0)
    uid = uuid.uuid4().hex[:12]
    full_file = InMemoryUploadedFile(
        full_buf, 'ImageField', f'{uid}.jpg',
        'image/jpeg', full_buf.getbuffer().nbytes, None
    )

    # Thumbnail
    thumb = img.copy()
    thumb.thumbnail((thumb_size, thumb_size), Image.LANCZOS)
    thumb_buf = io.BytesIO()
    thumb.save(thumb_buf, format='JPEG', quality=80)
    thumb_buf.seek(0)
    thumb_file = InMemoryUploadedFile(
        thumb_buf, 'ImageField', f'{uid}_thumb.jpg',
        'image/jpeg', thumb_buf.getbuffer().nbytes, None
    )

    return full_file, thumb_file
