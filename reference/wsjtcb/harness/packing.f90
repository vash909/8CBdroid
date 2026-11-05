! Test adapter only: packing, unpacking and hash algorithms remain upstream.
program packing_reference
  use packjt77, only: pack77
  use packjt77var, only: pack77var, unpack77var, ihashcall, &
       hash10var, hash12var, hash22var, save_hash_callvar
  use ft8_mod1, only: mycall, hiscall
  implicit none
  character(len=256) :: line
  character(len=77) :: bits
  character(len=37) :: message
  character(len=13) :: callsign, resolved
  integer :: status, i3, n3, width, value
  logical :: success

  mycall = ''
  hiscall = ''
  call fillhashvar(1, .false.)
  do
    read(*, '(A)', iostat=status) line
    if (status < 0) exit
    if (status /= 0 .or. line(2:2) /= '|') stop 2
    select case (line(1:1))
    case ('P', 'V')
      message = line(3:)
      i3 = -1
      n3 = -1
      if (line(1:1) == 'P') then
        call pack77(message, i3, n3, bits)
      else
        call pack77var(message, i3, n3, bits, 1)
      endif
      write(*, '(I0,A,I0,A,A)') i3, '|', n3, '|', bits
    case ('D')
      bits = line(3:)
      call unpack77var(bits, 1, message, success, 1)
      write(*, '(I0,A,A)') merge(1, 0, success), '|', trim(message)
    case ('H')
      callsign = line(3:)
      write(*, '(I0,A,I0,A,I0)') ihashcall(callsign, 10), '|', &
           ihashcall(callsign, 12), '|', ihashcall(callsign, 22)
    case ('L')
      callsign = line(3:)
      call save_hash_callvar(callsign, 1)
    case ('C')
      call fillhashvar(1, .true.)
      call fillhashvar(1, .false.)
    case ('S')
      mycall = line(3:)
      call fillhashvar(1, .false.)
    case ('Q')
      read(line(3:), *) width, value
      select case (width)
      case (10)
        call hash10var(value, resolved, 1)
      case (12)
        call hash12var(value, resolved, 1)
      case (22)
        call hash22var(value, resolved, 1)
      case default
        stop 3
      end select
      write(*, '(A)') trim(resolved)
    case default
      stop 4
    end select
  enddo
end program packing_reference
