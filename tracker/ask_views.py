"""Endpoints for the Ask chat page: saved chats, the streaming send, map cards.

Web only (session + CSRF); the Android app keeps using ``/api/ask/``. Nothing
here touches location data. A turn writes only the chat's own rows
(``AskChat`` / ``AskMessage`` / ``AskProposal``); a flight, journal entry or
place changes only through ``proposal_approve_api``, i.e. the user's click.
"""
import json
import logging

from django.contrib.auth.decorators import login_required
from django.http import JsonResponse, StreamingHttpResponse
from django.utils import timezone
from django.views.decorators.http import require_GET, require_POST

from . import ai_tasks, ask_edits, ask_map
from .models import AskChat, AskMessage, AskProposal, UserProfile

logger = logging.getLogger(__name__)

_MAX_MESSAGE_CHARS = 4000
_TITLE_CHARS = 60


def _gate(request):
    """(profile, error response). The Ask page needs AI switched on and set up."""
    profile, _ = UserProfile.objects.get_or_create(user=request.user)
    if not profile.ai_ask_enabled:
        return None, JsonResponse({'error': 'AI Ask is disabled.'}, status=403)
    if not profile.ai_configured:
        return None, JsonResponse({'error': 'AI Ask is not configured.'}, status=503)
    return profile, None


def _body(request):
    try:
        data = json.loads(request.body or '{}')
    except (json.JSONDecodeError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def _chat_row(chat):
    return {'id': chat.id, 'title': chat.title or 'New chat', 'pinned': chat.pinned,
            'mode': chat.mode or '', 'updated_at': chat.updated_at.isoformat()}


def _msg_row(m):
    return {'id': m.id, 'role': m.role, 'content': m.content,
            'cards': m.cards or [], 'meta': m.meta or {},
            'proposals': [ask_edits.serialize(p) for p in m.proposals.all()]}


@login_required
@require_GET
def chats_api(request):
    _, err = _gate(request)
    if err:
        return err
    qs = AskChat.objects.filter(user=request.user)
    q = (request.GET.get('q') or '').strip()[:100]
    if q:
        # Titles and message text; distinct() since several messages can match.
        from django.db.models import Q
        qs = qs.filter(Q(title__icontains=q) | Q(messages__content__icontains=q)).distinct()
    chats = qs.order_by('-pinned', '-updated_at')[:300]
    return JsonResponse({'chats': [_chat_row(c) for c in chats]})


@login_required
@require_GET
def chat_detail_api(request, chat_id):
    _, err = _gate(request)
    if err:
        return err
    chat = AskChat.objects.filter(user=request.user, pk=chat_id).first()
    if chat is None:
        return JsonResponse({'error': 'Not found.'}, status=404)
    return JsonResponse({
        **_chat_row(chat),
        'messages': [_msg_row(m) for m in chat.messages.prefetch_related('proposals')],
    })


@login_required
@require_POST
def chat_rename_api(request, chat_id):
    _, err = _gate(request)
    if err:
        return err
    data = _body(request)
    title = str((data or {}).get('title') or '').strip()[:120]
    if not title:
        return JsonResponse({'error': 'A title is required.'}, status=400)
    updated = AskChat.objects.filter(user=request.user, pk=chat_id).update(title=title, titled=True)
    if not updated:
        return JsonResponse({'error': 'Not found.'}, status=404)
    return JsonResponse({'status': 'ok', 'title': title})


@login_required
@require_POST
def chat_pin_api(request, chat_id):
    _, err = _gate(request)
    if err:
        return err
    data = _body(request) or {}
    pinned = bool(data.get('pinned', True))
    if not AskChat.objects.filter(user=request.user, pk=chat_id).update(pinned=pinned):
        return JsonResponse({'error': 'Not found.'}, status=404)
    return JsonResponse({'status': 'ok', 'pinned': pinned})


@login_required
@require_POST
def chat_delete_api(request, chat_id):
    """Deletes the conversation only — it is a record of questions and answers,
    and nothing in it is a handle on location data."""
    _, err = _gate(request)
    if err:
        return err
    AskChat.objects.filter(user=request.user, pk=chat_id).delete()
    return JsonResponse({'status': 'ok'})


# Generous for a person, and a ceiling for a script: a turn can run several
# tool rounds, each a streamed provider call holding a gthread worker.
_SEND_LIMIT_PER_HOUR = 120


def _line(event):
    return json.dumps(event, default=str) + '\n'


@login_required
@require_POST
def send_api(request):
    """One turn, streamed as newline-delimited JSON events.

    ``{"message", "chat_id"?, "mode"?, "tz_offset"}`` asks something new.
    ``{"chat_id", "regenerate": true, "message"?}`` re-answers the last question,
    optionally after editing it. The question is saved first; the reply when it
    finishes — or when the browser stops/disconnects, with whatever arrived. On
    a provider failure everything this turn did is undone, so a retry starts
    clean: a new question is taken back out, a replaced reply is kept."""
    profile, err = _gate(request)
    if err:
        return err
    # Each turn holds a worker for as long as the provider streams, against a
    # base URL the user chose, so turns are counted per account.
    from .views import _rate_limited
    if _rate_limited(request, 'ask_send', _SEND_LIMIT_PER_HOUR, 3600,
                     identifier=f'u{request.user.id}', include_ip=False):
        return JsonResponse({'error': "You've sent a lot of questions this hour. "
                                      'Try again in a little while.'}, status=429)
    data = _body(request)
    if data is None:
        return JsonResponse({'error': 'Invalid request.'}, status=400)
    text = str(data.get('message') or '').strip()
    regenerate = bool(data.get('regenerate'))
    if len(text) > _MAX_MESSAGE_CHARS:
        return JsonResponse({'error': 'That message is too long.'}, status=400)
    if not text and not regenerate:
        return JsonResponse({'error': 'Ask something first.'}, status=400)
    tz_offset = data.get('tz_offset', 0)
    # Absent means "keep the chat's mode"; '' (Auto) clears it.
    mode = data.get('mode')
    if mode is not None and str(mode) not in ai_tasks.ASK_MODES:
        mode = ''

    created = False
    chat_id = data.get('chat_id')
    if chat_id not in (None, '') and not str(chat_id).isdigit():
        return JsonResponse({'error': 'Chat not found.'}, status=404)
    chat = AskChat.objects.filter(user=request.user, pk=chat_id).first() if chat_id else None
    if chat_id and chat is None:
        return JsonResponse({'error': 'Chat not found.'}, status=404)

    replaced = []        # assistant replies a regenerate supersedes (deleted on success)
    old_question = None  # the text an edit replaced, restored on failure
    if regenerate:
        if chat is None:
            return JsonResponse({'error': 'Nothing to regenerate.'}, status=400)
        user_msg = chat.messages.filter(role='user').order_by('-id').first()
        if user_msg is None:
            return JsonResponse({'error': 'Nothing to regenerate.'}, status=400)
        replaced = list(chat.messages.filter(id__gt=user_msg.id).values_list('id', flat=True))
        if text and text != user_msg.content:
            old_question = user_msg.content
            user_msg.content = text
            user_msg.save(update_fields=['content'])
        text = user_msg.content
    else:
        if chat is None:
            chat = AskChat.objects.create(user=request.user, title=text[:_TITLE_CHARS], mode=mode or '')
            created = True
        user_msg = AskMessage.objects.create(chat=chat, role='user', content=text)
    if mode is not None and mode != chat.mode:
        chat.mode = mode
        chat.save(update_fields=['mode'])
    mode = chat.mode

    def _turn(m):
        # The model only sees text, so a turn that proposed changes carries a
        # note of what the user decided — it must never believe a denied change
        # was saved, or that an approved one is still waiting.
        note = ask_edits.history_note(m.proposals.all()) if m.role == 'assistant' else ''
        return {'role': m.role, 'content': (m.content + '\n\n' + note) if note else m.content}

    history = [_turn(m) for m in chat.messages.exclude(id__in=replaced)
               .prefetch_related('proposals')
               .order_by('-id')[:ai_tasks._MAX_CLIENT_TURNS]][::-1]
    user = request.user
    tz = timezone.get_current_timezone()

    def stream():
        parts, cards, meta = [], [], {}
        proposal_ids = []
        saved = False

        def save():
            nonlocal saved
            if saved:
                return None
            saved = True
            body = ''.join(parts).strip()
            if not body and proposal_ids:
                body = 'Here is the change for you to review.'
            if not body:
                return None
            AskMessage.objects.filter(id__in=replaced).delete()
            msg = AskMessage.objects.create(chat=chat, role='assistant', content=body,
                                            cards=cards, meta=meta)
            # Only now can a proposal be approved (approve() requires a message):
            # one from a turn that never got saved stays inert.
            AskProposal.objects.filter(id__in=proposal_ids).update(message=msg)
            chat.save(update_fields=['updated_at'])
            return msg

        try:
            yield _line({'type': 'chat', **_chat_row(chat), 'user_message_id': user_msg.id})
            # The zone the middleware activated belongs to the request thread's
            # lifetime, which can end before a streamed body does.
            with timezone.override(tz):
                for ev in ai_tasks.ask_events(user, history, profile, tz_offset, mode=mode):
                    if ev['type'] == 'delta':
                        parts.append(ev['text'])
                    elif ev['type'] == 'map':
                        cards.append(ev['card'])
                    elif ev['type'] == 'proposal':
                        d = ev['draft']
                        p = AskProposal.objects.create(
                            user=user, chat=chat, kind=d['kind'], target_id=d.get('target_id'),
                            target_key=d.get('target_key') or '', summary=d['summary'],
                            payload=d.get('payload') or {}, before=d.get('before') or {},
                            rows=d.get('rows') or [])
                        proposal_ids.append(p.id)
                        ev = {'type': 'proposal', 'proposal': ask_edits.serialize(p)}
                    elif ev['type'] == 'done':
                        parts[:] = [ev['text']]
                        meta['sources'] = ev.get('sources') or []
                        if ev.get('stats'):
                            meta['stats'] = ev['stats']
                    yield _line(ev)
            msg = save()
            yield _line({'type': 'saved', **_chat_row(chat), 'message_id': msg.id if msg else None})

            # After the answer is safely stored: a title for a new chat and
            # three follow-up chips. The user is already reading by now.
            title, chips = ai_tasks.followups(profile, text, ''.join(parts))
            if msg and chips:
                meta['followups'] = chips
                AskMessage.objects.filter(pk=msg.pk).update(meta=meta)
            if title and not chat.titled:
                AskChat.objects.filter(pk=chat.pk).update(title=title, titled=True)
                chat.title, chat.titled = title, True
            yield _line({'type': 'followups', 'items': chips, **_chat_row(chat)})
        except ai_tasks.AIProviderError as e:
            if not saved:
                saved = True
                AskProposal.objects.filter(id__in=proposal_ids).delete()
                if regenerate:
                    if old_question is not None:
                        user_msg.content = old_question
                        user_msg.save(update_fields=['content'])
                else:
                    user_msg.delete()
                    if created:
                        chat.delete()
            yield _line({'type': 'error', 'error': str(e), 'chat_deleted': created and not chat.pk})
        except GeneratorExit:
            save()  # the browser stopped or left: keep what it got
            raise
        except Exception:
            logger.exception('ask send failed')
            save()
            yield _line({'type': 'error', 'error': 'Something went wrong answering that.'})

    # One JSON object per line. Labelled as an event stream because that is the
    # type proxies (a Cloudflare tunnel in front of most installs) pass through
    # unbuffered; the page reads lines itself and never uses EventSource.
    resp = StreamingHttpResponse(stream(), content_type='text/event-stream; charset=utf-8')
    resp['Cache-Control'] = 'no-store, no-transform'
    resp['X-Accel-Buffering'] = 'no'
    # GZipMiddleware leaves a response alone when it already names an encoding.
    # Its streaming compressor doesn't flush per chunk, so small deltas would sit
    # in zlib's buffer and the answer would arrive in lumps.
    resp['Content-Encoding'] = 'identity'
    return resp


@login_required
@require_GET
def map_api(request):
    """The points behind a map card, read from the signed-in user's own history."""
    _, err = _gate(request)
    if err:
        return err
    try:
        card = ask_map.clean_card(request.GET.dict())
    except ask_map.CardError as e:
        return JsonResponse({'error': str(e)}, status=400)
    geo = ask_map.geometry(request.user, card, request.GET.get('tz_offset', 0))
    geo.update(title=card['title'], card=card, url=ask_map.full_map_url(card))
    resp = JsonResponse(geo)
    resp['Cache-Control'] = 'no-store'
    return resp


@login_required
@require_POST
def proposal_approve_api(request, proposal_id):
    """The only way an Ask proposal changes anything: the user's own click.
    CSRF-checked and owner-scoped; applies exactly what the card showed."""
    _, err = _gate(request)
    if err:
        return err
    p = ask_edits.approve(request.user, proposal_id)
    if p is None:
        return JsonResponse({'error': 'Not found.'}, status=404)
    return JsonResponse({'proposal': ask_edits.serialize(p)})


@login_required
@require_POST
def proposal_deny_api(request, proposal_id):
    _, err = _gate(request)
    if err:
        return err
    p = ask_edits.deny(request.user, proposal_id)
    if p is None:
        return JsonResponse({'error': 'Not found.'}, status=404)
    return JsonResponse({'proposal': ask_edits.serialize(p)})
