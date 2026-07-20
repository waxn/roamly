from django import forms
from django.conf import settings
from django.contrib.auth.forms import UserCreationForm
from django.contrib.auth.models import User

from .models import APIKey, Adventure


class SignUpForm(UserCreationForm):
    email = forms.EmailField(required=False)
    admin_key = forms.CharField(required=False, widget=forms.PasswordInput)

    class Meta:
        model = User
        fields = ('username', 'email', 'password1', 'password2')

    def clean_email(self):
        """Enforce case-insensitive email uniqueness so email-based login stays
        unambiguous. Only checked at signup — the only place email is set."""
        email = (self.cleaned_data.get('email') or '').strip()
        if email and User.objects.filter(email__iexact=email).exists():
            raise forms.ValidationError('An account with this email already exists.')
        return email

    def clean_admin_key(self):
        """An admin key, if given, must match ADMIN_SIGNUP_KEY (which must be set)."""
        key = self.cleaned_data.get('admin_key', '')
        if not key:
            return key
        expected = getattr(settings, 'ADMIN_SIGNUP_KEY', '') or ''
        if not expected or key != expected:
            raise forms.ValidationError('Invalid admin key.')
        return key

    @property
    def is_admin_signup(self):
        return bool(self.cleaned_data.get('admin_key'))


class APIKeyForm(forms.ModelForm):
    class Meta:
        model = APIKey
        fields = ('name',)


class AdventureForm(forms.ModelForm):
    start_time = forms.DateTimeField(
        widget=forms.DateTimeInput(attrs={'type': 'datetime-local'}),
        input_formats=['%Y-%m-%dT%H:%M']
    )
    end_time = forms.DateTimeField(
        widget=forms.DateTimeInput(attrs={'type': 'datetime-local'}),
        input_formats=['%Y-%m-%dT%H:%M']
    )

    class Meta:
        model = Adventure
        fields = ('name', 'description', 'start_time', 'end_time')
