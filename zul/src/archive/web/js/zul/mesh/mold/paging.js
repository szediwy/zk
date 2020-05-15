/* paging.js

	Purpose:
		
	Description:	
		
	History:
		Fri Jan 23 15:00:45     2009, Created by jumperchen

Copyright (C) 2008 Potix Corporation. All Rights Reserved.

This program is distributed under LGPL Version 2.1 in the hope that
it will be useful, but WITHOUT ANY WARRANTY.
*/
function (out) {
	if (this.getMold() == "os") {
		out.push('<div name="', this.uuid, '"', this.domAttrs_(), '>', this._innerTags(), '</div>');
		return;
	}
	
	var uuid = this.uuid,
		btn = this.$s('button'),
		showFirstLast = this._showFirstLast ? '' : 'display: none;';
	
	out.push('<nav name="', uuid, '"', this.domAttrs_(), ' aria-label="paging of ', uuid, '"><ul>',
			'<li style="' , showFirstLast, '"><button name="', uuid, '-first" class="', btn, ' ', this.$s('first'),
				'"><i class="z-paging-icon z-icon-angle-double-left"></i></button></li>',
			'<li><button name="', uuid, '-prev" class="', btn, ' ', this.$s('previous'),
				'"><i class="z-paging-icon z-icon-angle-left"></i></button></li>',
			'<li><input name="',
				uuid, '-real" class="', this.$s('input'), '" type="text" value="',
				this.getActivePage() + 1, '" size="3"></input><span class="',
				this.$s('text'), '"> / ', this.getPageCount(), '</span></li>',
			'<li><button name="', uuid, '-next" class="', btn, ' ', this.$s('next'),
				'"><i class="z-paging-icon z-icon-angle-right"></i></button></li>',
			'<li style="' , showFirstLast, '"><button name="', uuid, '-last" class="', btn, ' ', this.$s('last'),
				'"><i class="z-paging-icon z-icon-angle-double-right"></i></button></li></ul>');
	
	if (this.isDetailed())
		this._infoTags(out);
	out.push('</nav>');
}
