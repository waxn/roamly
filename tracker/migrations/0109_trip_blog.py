"""Trip blog: posts, media, comments, reactions, share links, digest subscribers."""

import django.db.models.deletion
import django.utils.timezone
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('tracker', '0108_achievements'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='TripBlog',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('name', models.CharField(max_length=200)),
                ('subtitle', models.CharField(blank=True, default='', max_length=300)),
                ('cover_image', models.ImageField(blank=True, null=True, upload_to='tripblog/covers/')),
                ('cover_thumbnail', models.ImageField(blank=True, null=True, upload_to='tripblog/covers/thumbs/')),
                ('start_date', models.DateField()),
                ('end_date', models.DateField(blank=True, null=True)),
                ('invite_token', models.CharField(blank=True, max_length=32, null=True, unique=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('creator', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='trip_blogs_created', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-start_date', '-id'],
            },
        ),
        migrations.CreateModel(
            name='TripPost',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('client_id', models.CharField(blank=True, default='', max_length=64)),
                ('text', models.TextField(blank=True, default='')),
                ('taken_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('latitude', models.FloatField(blank=True, null=True)),
                ('longitude', models.FloatField(blank=True, null=True)),
                ('place_name', models.CharField(blank=True, default='', max_length=200)),
                ('city', models.CharField(blank=True, default='', max_length=200)),
                ('state', models.CharField(blank=True, default='', max_length=200)),
                ('country', models.CharField(blank=True, default='', max_length=200)),
                ('country_code', models.CharField(blank=True, default='', max_length=2)),
                ('local_date', models.DateField(blank=True, null=True)),
                ('published', models.BooleanField(default=False)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('edited_at', models.DateTimeField(blank=True, null=True)),
                ('author', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='trip_posts', to=settings.AUTH_USER_MODEL)),
                ('blog', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='posts', to='tracker.tripblog')),
            ],
            options={
                'ordering': ['-taken_at', '-id'],
            },
        ),
        migrations.CreateModel(
            name='TripComment',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('guest_name', models.CharField(blank=True, default='', max_length=60)),
                ('text', models.TextField()),
                ('viewer_key', models.CharField(blank=True, default='', max_length=64)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('member', models.ForeignKey(blank=True, null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='+', to=settings.AUTH_USER_MODEL)),
                ('post', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='comments', to='tracker.trippost')),
            ],
            options={
                'ordering': ['created_at', 'id'],
            },
        ),
        migrations.CreateModel(
            name='TripPostMedia',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('order', models.PositiveIntegerField(default=0)),
                ('media_type', models.CharField(default='image', max_length=10)),
                ('image', models.ImageField(blank=True, upload_to='tripblog/photos/')),
                ('thumbnail', models.ImageField(blank=True, null=True, upload_to='tripblog/photos/thumbs/')),
                ('video', models.FileField(blank=True, null=True, upload_to='tripblog/videos/')),
                ('poster', models.ImageField(blank=True, null=True, upload_to='tripblog/videos/posters/')),
                ('status', models.CharField(choices=[('ready', 'Ready'), ('pending', 'Pending'), ('processing', 'Processing'), ('failed', 'Failed')], default='ready', max_length=12)),
                ('width', models.IntegerField(blank=True, null=True)),
                ('height', models.IntegerField(blank=True, null=True)),
                ('duration_s', models.FloatField(blank=True, null=True)),
                ('upload_id', models.CharField(blank=True, default='', max_length=32)),
                ('chunks_received', models.IntegerField(default=0)),
                ('total_chunks', models.IntegerField(default=0)),
                ('error', models.CharField(blank=True, default='', max_length=300)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('post', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='media', to='tracker.trippost')),
            ],
            options={
                'ordering': ['order', 'id'],
            },
        ),
        migrations.CreateModel(
            name='TripReaction',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('emoji', models.CharField(max_length=8)),
                ('viewer_key', models.CharField(max_length=64)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('post', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='reactions', to='tracker.trippost')),
            ],
        ),
        migrations.CreateModel(
            name='TripShareLink',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('token', models.CharField(db_index=True, max_length=64, unique=True)),
                ('label', models.CharField(blank=True, default='', max_length=120)),
                ('expires_at', models.DateTimeField(blank=True, null=True)),
                ('max_uses', models.IntegerField(blank=True, null=True)),
                ('use_count', models.IntegerField(default=0)),
                ('pin_hash', models.CharField(blank=True, default='', max_length=128)),
                ('revoked_at', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('blog', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='share_links', to='tracker.tripblog')),
                ('created_by', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='+', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'ordering': ['-created_at'],
            },
        ),
        migrations.CreateModel(
            name='TripShareUse',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('viewer_key', models.CharField(max_length=64)),
                ('guest_name', models.CharField(blank=True, default='', max_length=60)),
                ('first_at', models.DateTimeField(auto_now_add=True)),
                ('last_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('visits', models.IntegerField(default=1)),
                ('link', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='uses', to='tracker.tripsharelink')),
            ],
            options={
                'ordering': ['-last_at'],
            },
        ),
        migrations.CreateModel(
            name='TripSubscriber',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('email', models.EmailField(max_length=254)),
                ('token', models.CharField(max_length=32, unique=True)),
                ('confirmed_at', models.DateTimeField(blank=True, null=True)),
                ('unsubscribed_at', models.DateTimeField(blank=True, null=True)),
                ('last_sent_at', models.DateTimeField(blank=True, null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('blog', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='subscribers', to='tracker.tripblog')),
                ('link', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='subscribers', to='tracker.tripsharelink')),
            ],
        ),
        migrations.CreateModel(
            name='TripBlogMember',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('role', models.CharField(choices=[('owner', 'Owner'), ('member', 'Member')], default='member', max_length=10)),
                ('joined_at', models.DateTimeField(auto_now_add=True)),
                ('accepted_at', models.DateTimeField(blank=True, null=True)),
                ('share_location', models.BooleanField(default=False)),
                ('featured', models.BooleanField(default=False)),
                ('blog', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='members', to='tracker.tripblog')),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='trip_blog_memberships', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'unique_together': {('blog', 'user')},
            },
        ),
        migrations.AddIndex(
            model_name='trippost',
            index=models.Index(fields=['blog', 'published', '-local_date'], name='tracker_tpost_feed_idx'),
        ),
        migrations.AddConstraint(
            model_name='trippost',
            constraint=models.UniqueConstraint(condition=models.Q(('client_id', ''), _negated=True), fields=('author', 'client_id'), name='tracker_tpost_client_uniq'),
        ),
        migrations.AlterUniqueTogether(
            name='tripreaction',
            unique_together={('post', 'viewer_key', 'emoji')},
        ),
        migrations.AlterUniqueTogether(
            name='tripshareuse',
            unique_together={('link', 'viewer_key')},
        ),
        migrations.AlterUniqueTogether(
            name='tripsubscriber',
            unique_together={('blog', 'email')},
        ),
    ]
